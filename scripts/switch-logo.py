#!/usr/bin/env python3
"""Generate every brand consumer from brand.conf + web/brand/logos.json.

Example: python3 scripts/switch-logo.py stand-illustrator --website PATH --auth-repo PATH
Use --check to detect drift without writing. Publishing is a separate explicit step.

Catalog entry extension (brand-standard batch 2026-10-19): an entry may declare
  avatarLight/avatarDark — bare symbol-mark sources (no tile). When present they
  are rendered into the release dir as symbol-{light,dark}-128.png and the
  manifest logoUrl/logoBright4/logoDark4 (console avatar slots) point there,
  while the favicon/app-icon family keeps coming from the tiled light/dark
  sources. `svg` (optional) names a light-theme app-icon SVG; it is published
  as the release favicon.svg with a prefers-color-scheme dark swap and the
  manifest faviconSvg field.
"""
import argparse, base64, hashlib, io, json, re, sys
from pathlib import Path
from PIL import Image
ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / 'src/main/resources/web'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('logo', nargs='?')
    p.add_argument('--list', action='store_true')
    p.add_argument('--website', type=Path)
    p.add_argument('--auth-repo', type=Path)
    p.add_argument('--check', action='store_true')
    p.add_argument('--client-only', action='store_true', help='explicitly generate only client/packaging outputs')
    a = p.parse_args()
    catalog = json.loads((WEB / 'brand/logos.json').read_text())
    if a.list:
        for key, item in catalog.items(): print(f'{key:16} {item["name"]}')
        return
    if not a.client_only and not (a.website and a.auth_repo):
        p.error('global sync requires both --website and --auth-repo; use --client-only for a local build')
    conf = (ROOT / 'brand.conf').read_text()
    selected = re.search(r'^activeLogo\s*=\s*([^\s#]+)', conf, re.M)
    active = a.logo or (selected.group(1) if selected else 'stand-illustrator')
    if active not in catalog: p.error('unknown logo; use --list')
    item = catalog[active]
    sources = {k: (WEB / item[k].lstrip('/')).read_bytes() for k in ('light', 'dark')}
    avatar_sources = ({k: (WEB / item[k].lstrip('/')).read_bytes()
                       for k in ('avatarLight', 'avatarDark') if k in item})
    if len(avatar_sources) not in (0, 2): p.error('avatarLight and avatarDark must be declared together')
    svg_source = (WEB / item['svg'].lstrip('/')).read_text() if 'svg' in item else None
    digest_material = sources['light'] + sources['dark']
    for data in avatar_sources.values(): digest_material += data
    if svg_source is not None: digest_material += svg_source.encode()
    digest = hashlib.sha256(digest_material).hexdigest()[:16]
    base = f'/brand/releases/{digest}'
    sources.update(avatar_sources)
    output = {}
    def put(path, data): output[path] = data.encode() if isinstance(data, str) else data
    def js(value): return json.dumps(value, ensure_ascii=False, indent=2) + '\n'
    method = Image.Resampling.NEAREST if item['resample'] == 'nearest' else Image.Resampling.LANCZOS
    def render(variant, size, fmt='PNG'):
        src = Image.open(io.BytesIO(sources[variant])).convert('RGBA')
        scale = min(size / src.width, size / src.height)
        src = src.resize((max(1, round(src.width * scale)), max(1, round(src.height * scale))), method)
        canvas = Image.new('RGBA', (size, size))
        canvas.paste(src, ((size-src.width)//2, (size-src.height)//2))
        buf = io.BytesIO()
        opts = {'sizes': [(n,n) for n in (16,24,32,48,64,128,256)]} if fmt == 'ICO' else {}
        canvas.save(buf, format=fmt, **opts)
        return buf.getvalue()
    def dark_aware_favicon(svg_text):
        """Publish the light app-icon SVG as favicon.svg with a dark-scheme
        swap: tile takes the glyph fill and vice versa (author package
        convention: light = white tile + black mark, dark = inverse)."""
        rect = re.search(r'<rect\b[^>]*>', svg_text); grp = re.search(r'<g\b[^>]*>', svg_text)
        if not (rect and grp): p.error('app-icon svg must declare a rect tile and a glyph group')
        tile = re.search(r'fill="([^"]+)"', rect.group(0)); glyph = re.search(r'fill="([^"]+)"', grp.group(0))
        if not (tile and glyph): p.error('app-icon svg rect/glyph must carry fill attributes')
        style = ('<style>@media (prefers-color-scheme: dark){.bgt{fill:%s}.fgs{fill:%s}}</style>'
                 % (glyph.group(1), tile.group(1)))
        out = svg_text.replace(rect.group(0), rect.group(0).replace('<rect', '<rect class="bgt"', 1), 1)
        out = out.replace(grp.group(0), grp.group(0).replace('<g', '<g class="fgs"', 1), 1)
        head = out.index('>') + 1
        return out[:head] + style + out[head:]
    sizes = (16,32,48,64,128,180,192,512)
    files = {f'{v}-{n}.png': render(v,n) for v in ('light','dark') for n in sizes}
    files['favicon.ico'] = render('light',256,'ICO')
    if avatar_sources:
        files['symbol-light-128.png'] = render('avatarLight',128)
        files['symbol-dark-128.png'] = render('avatarDark',128)
    if svg_source is not None: files['favicon.svg'] = dark_aware_favicon(svg_source)
    for name, data in files.items(): put(WEB / base.lstrip('/') / name, data)
    origin = re.search(r'^profileUrl\s*=\s*(https://[^/\s]+)',conf,re.M).group(1)
    bright4 = base + ('/symbol-light-128.png' if avatar_sources else '/light-128.png')
    dark4 = base + ('/symbol-dark-128.png' if avatar_sources else '/dark-128.png')
    manifest = {'schemaVersion':1,'active':active,'revision':digest,
                'sourceSha256':{k:hashlib.sha256(sources[k]).hexdigest() for k in ('light','dark')},
                'logo':base+'/light-512.png','logoBright4':bright4,
                'logoDark4':dark4,'emailLogo':origin+base+'/light-512.png',
                'faviconBase':base,'appleTouch':base+'/light-180.png',
                'faviconSvg':(base+'/favicon.svg') if svg_source is not None else '','logtoDir':base+'/'}
    put(WEB/'brand/current.json',js(manifest))
    for key,value in [('logoUrl',manifest['logoBright4']),('logoDarkUrl',manifest['logoDark4'])]:
        conf,count = re.subn(rf'^{key}\s*=.*$',f'{key} = {value}',conf,flags=re.M)
        if count != 1: p.error(f'brand.conf must define one {key}')
    if selected: conf = re.sub(r'^activeLogo\s*=.*$',f'activeLogo = {active}',conf,flags=re.M)
    else: conf += f'activeLogo = {active}\n'
    put(ROOT/'brand.conf',conf)
    for n in (16,32,180,192,512): put(WEB/f'favicon-{n}.png',files[f'light-{n}.png'])
    put(WEB/'favicon.ico',files['favicon.ico'])
    if svg_source is not None: put(WEB/'favicon.svg',files['favicon.svg'])
    for name,fmt,size in [('nebflow.png','PNG',512),('nebflow.ico','ICO',256),('nebflow.icns','ICNS',1024)]:
        put(ROOT/'packaging/icons'/name,render('light',size,fmt))
    index=(WEB/'index.html').read_text()
    index=re.sub(r'(favicon(?:-\d+\.png|\.ico|\.svg))\?v=[^"\s]+',rf'\1?v={digest}',index)
    put(WEB/'index.html',index)
    # Static fallback is generated too; injected runtime settings still take priority.
    brandjs=(WEB/'js/brand.js').read_text()
    for key in ('logoUrl','logoDarkUrl'):
        val=manifest['logoBright4' if key=='logoUrl' else 'logoDark4']
        brandjs=re.sub(rf"({key}: )'[^']+'",rf"\1'{val}'",brandjs,count=1)
    put(WEB/'js/brand.js',brandjs)
    if a.website:
        w=a.website.resolve()
        if not (w/'lib/brand.ts').is_file(): p.error('invalid website repository')
        put(w/'lib/brand-assets.json',js(manifest))
        put(w/'lib/brand-og.ts', '// Generated by Stand scripts/switch-logo.py; do not edit.\nexport default '+json.dumps('data:image/png;base64,'+base64.b64encode(files['light-512.png']).decode())+';\n')
        put(w/'public/brand/current.json',js(manifest))
        for name,data in files.items(): put(w/'public'/base.lstrip('/')/name,data)
        put(w/'public/favicon.ico',files['favicon.ico'])
        # Historical email URLs remain valid; all new emails use the revision URL.
        for name in ('email-logo.png','brand/email-logo.png'): put(w/'public'/name,files['light-512.png'])
        m=json.loads((w/'public/manifest.webmanifest').read_text())
        m['icons']=[{'src':base+f'/light-{n}.png','sizes':f'{n}x{n}','type':'image/png','purpose':'any'} for n in (192,512)]
        put(w/'public/manifest.webmanifest',js(m))
    if a.auth_repo:
        ui=a.auth_repo.resolve()/'deploy/docker/byui/auth-ui'
        if not (ui/'src/views/brand.ts').is_file(): p.error('invalid auth repository')
        put(ui/'src/assets/brand-current.png',files['light-512.png'])
        put(ui/'src/assets/brand-current-dark.png',files['dark-512.png'])
        put(ui/'src/assets/brand-favicon.png',files['light-48.png'])
        put(ui/'src/assets/brand-current.json',js(manifest))
    changed=[path for path,data in output.items() if not path.exists() or path.read_bytes()!=data]
    if a.check:
        for path in changed: print('DRIFT',path)
        print(f'{active} ({digest}): {len(output)} outputs checked; {len(changed)} differ')
        sys.exit(bool(changed))
    for path in changed:
        path.parent.mkdir(parents=True,exist_ok=True)
        temp=path.with_name(path.name+'.brand-tmp');temp.write_bytes(output[path]);temp.replace(path)
    print(f'{active} ({digest}): synchronized {len(changed)} of {len(output)} outputs. Build/publish consumers to activate.')

if __name__ == '__main__': main()
