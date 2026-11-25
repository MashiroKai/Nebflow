#!/usr/bin/env python3
import http.client,json,os,ssl,subprocess,tempfile,threading,time,uuid,importlib.util,types
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
R=Path(__file__).resolve().parent; E=Path(os.environ['RELAY_EVIDENCE_DIR'])/('https-relay-test-'+uuid.uuid4().hex); E.mkdir(mode=0o700)
seen=[]
class Up(BaseHTTPRequestHandler):
 def log_message(self,*a): pass
 def do_POST(self):
  self.rfile.read(int(self.headers.get('Content-Length','0'))); auth=self.headers.get('Authorization',''); cookie=self.headers.get('Cookie'); xkey=self.headers.get('x-api-key')
  seen.append({'path':self.path,'host':self.headers.get('Host'),'forwarded_absent':all(self.headers.get(x) is None for x in ('Forwarded','X-Forwarded-Host','X-Forwarded-Server','X-Original-URL')),'expected_fake_key':auth=='Bearer fake-test-key','cookie_absent':cookie is None,'xkey_absent':xkey is None})
  if self.path.endswith('/redirect'):
   self.send_response(302); self.send_header('Location','https://invalid.example/escape'); self.end_headers(); return
  self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers(); self.wfile.write(b'data: ok\n\ndata: [DONE]\n\n')
with tempfile.TemporaryDirectory() as d:
 cert=Path(d)/'cert.pem'; key=Path(d)/'key.pem'
 subprocess.run(['/usr/bin/openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
 up=ThreadingHTTPServer(('127.0.0.1',0),Up); context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(cert,key); up.socket=context.wrap_socket(up.socket,server_side=True); threading.Thread(target=up.serve_forever,daemon=True).start()
 env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-test-key'}
 def spawn(label,limit=25,seconds=30):
  # A separate imported-module child injects the local TLS dialer; production CLI has no override.
  code='''import importlib.util,sys,types,ssl,http.client,os,json,threading\ns=importlib.util.spec_from_file_location("relay",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["good,example.com,/v1,FAKE_KEY","second,other.example,/v1,FAKE_KEY"],allow_host=["example.com","other.example"],ledger=sys.argv[2],limit=int(sys.argv[3]),seconds=float(sys.argv[4]),timeout=5)\nctx=ssl.create_default_context(cafile=sys.argv[5]); r=m.Relay(a,dialer=lambda h,p,t:http.client.HTTPSConnection("127.0.0.1",int(sys.argv[6]),timeout=t,context=ctx))\nclass H(m.BaseHTTPRequestHandler):\n def log_message(self,*a): pass\n def do_POST(self): r.handle(self)\nserver=m.Server(("127.0.0.1",0),H); print(json.dumps({"port":server.server_port}),flush=True); timer=threading.Timer(a.seconds,lambda:os._exit(0)); timer.daemon=True; timer.start(); server.serve_forever()'''
  args=['/usr/bin/python3','-c',code,str(R/'https_relay.py'),str(E/(label+'.jsonl')),str(limit),str(seconds),str(cert),str(up.server_port)]
  p=subprocess.Popen(args,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True); info=json.loads(p.stdout.readline()); return p,info['port']
 def post(port,path,host=None):
  headers={'Authorization':'Bearer attacker','x-api-key':'attacker','Cookie':'attacker','X-Forwarded-Host':'attacker.example','Forwarded':'host=attacker.example','X-Forwarded-Server':'attacker','X-Original-URL':'/evil','X-Stand-Trace-Id':'test_1'}
  if host is not None: headers['Host']=host
  c=http.client.HTTPConnection('127.0.0.1',port,timeout=5); c.request('POST',path,b'{}',headers); r=c.getresponse(); data=r.read(); s=r.status; c.close(); return s,data
 result={}
 try:
  p,port=spawn('functional'); checks={}
  def rejected(name,path,host=None):
   before=len(seen); status,_=post(port,path,host); checks[name]=(status==403 and len(seen)==before)
  rejected('provider_host_rejected','/good/chat','example.com')
  rejected('wrong_port_host_rejected','/good/chat','127.0.0.1:1')
  rejected('absolute_uri_rejected','http://example.com/good/chat')
  rejected('missing_mapping_rejected','/bad/chat')
  rejected('suffix_impostor_rejected','/example.com.evil.example/chat')
  status,body=post(port,'/good/chat'); checks['first_host_and_stream']=(status==200 and seen[-1]['host']=='example.com' and body==b'data: ok\n\ndata: [DONE]\n\n')
  status,_=post(port,'/second/chat'); checks['second_host']=(status==200 and seen[-1]['host']=='other.example')
  checks['forwarded_headers_stripped']=all(x['forwarded_absent'] for x in seen)
  checks['auth_cookie_stripped_and_injected']=all(x['expected_fake_key'] and x['cookie_absent'] and x['xkey_absent'] for x in seen)
  before=len(seen); status,_=post(port,'/good/redirect'); checks['redirect_not_followed']=(status==502 and len(seen)==before+1)
  p.terminate(); p.wait(timeout=5)
  (E/'result.json').write_text(json.dumps({'checks':checks,'count':len(checks),'failed':[k for k,v in checks.items() if not v],'upstream_observations':seen,'limits':'local override; production 443 and exact target host validated statically only'},indent=2)+'\n')
  print(E); assert all(checks.values()),checks
 finally: up.shutdown(); up.server_close()
