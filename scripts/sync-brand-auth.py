#!/usr/bin/env python3
"""Sync Logto hosted branding and every email template from the generated brand manifest.

Default is a read-only check. Use --apply after publishing website assets.
Credentials are read from a caller-provided dotenv file, never printed or saved.
"""
import argparse, base64, json, re, sys, urllib.request, urllib.parse
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent

def envfile(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#') or '=' not in line: continue
        k,v=line.strip().removeprefix('export ').split('=',1);v=v.strip()
        if len(v)>1 and v[0]==v[-1] and v[0] in ['"', "'"]: v=v[1:-1]
        result[k.strip()]=v
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--env-file',type=Path,required=True)
    p.add_argument('--endpoint',default='https://auth.mystand.space')
    p.add_argument('--apply',action='store_true')
    a=p.parse_args();cfg=envfile(a.env_file)
    manifest=json.loads((ROOT/'src/main/resources/web/brand/current.json').read_text())
    def req(route,body=None,method='GET',token=None):
        headers={'Authorization':'Bearer '+token} if token else {}
        if body is not None: headers['Content-Type']='application/json'
        q=urllib.request.Request(a.endpoint.rstrip('/')+route,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
        with urllib.request.urlopen(q,timeout=15) as r: return json.load(r) if r.status!=204 else None
    data=urllib.parse.urlencode({'grant_type':'client_credentials','client_id':cfg['LOGTO_M2M_CLIENT_ID'], 'client_secret':cfg['LOGTO_M2M_CLIENT_SECRET'],'resource':cfg['LOGTO_MANAGEMENT_RESOURCE'],'scope':'all'}).encode()
    q=urllib.request.Request(a.endpoint+'/oidc/token',data=data)
    with urllib.request.urlopen(q,timeout=15) as r: token=json.load(r)['access_token']
    def inline(path):
        data=(ROOT/'src/main/resources/web'/path.lstrip('/')).read_bytes()
        return 'data:image/png;base64,'+base64.b64encode(data).decode()
    # The auth host's CSP admits self/data images. Derive inline artwork from
    # the same manifest rather than widening the production CSP.
    branding={'logoUrl':inline(manifest['logoBright4']),'darkLogoUrl':inline(manifest['logoDark4']),
              'favicon':inline(manifest['faviconBase']+'/light-48.png'),'darkFavicon':inline(manifest['faviconBase']+'/dark-48.png')}
    exp=req('/api/sign-in-exp',token=token)
    connectors=req('/api/connectors',token=token)
    changes=[]
    if any(exp.get('branding',{}).get(k)!=v for k,v in branding.items()): changes.append(('branding',None,branding))
    templates_count=0
    # Restrict replacement to known product logo URLs. Never rewrite tracking/social images.
    pattern=r'''(<img\b[^>]*?\bsrc\s*=\s*["'])(https://(?:nebflow\.space|mystand\.space)/(?:email-logo\.png|brand/[^"']+))(["'])'''
    for connector in connectors:
        if connector.get('type')!='Email': continue
        config=connector.get('config',{});changed=False
        for template in config.get('templates',[]):
            content=template.get('content','')
            updated,n=re.subn(pattern,lambda m:m[1]+manifest['emailLogo']+m[3],content,flags=re.I)
            if n: templates_count+=1
            if updated!=content:template['content']=updated;changed=True
        if changed: changes.append(('email',connector['id'],config))
    print(f'Brand {manifest["active"]} / {manifest["revision"]}; email logo slots: {templates_count}; changes: {len(changes)}')
    if not a.apply: sys.exit(1 if changes else 0)
    # Confirm the exact public artwork is live before sending emails to it.
    url=manifest['emailLogo'];expected=(ROOT/'src/main/resources/web'/urllib.parse.urlsplit(url).path.lstrip('/')).read_bytes()
    with urllib.request.urlopen(url,timeout=15) as r: actual=r.read()
    if actual!=expected:sys.exit('Published logo differs from generated source; refusing auth update')
    for kind,ident,value in changes:
        if kind=='branding':req('/api/sign-in-exp',{'branding':value},'PATCH',token)
        else:req('/api/connectors/'+ident,{'config':value},'PATCH',token)
        print('Updated',kind)
    print('Brand sync complete. Re-run without --apply to verify drift is zero.')

if __name__=='__main__':main()
