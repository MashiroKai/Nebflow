#!/usr/bin/env python3
import importlib.util, os, pathlib, types, uuid, json
from unittest.mock import patch
root=pathlib.Path(__file__).resolve().parent; out=pathlib.Path(os.environ['RELAY_EVIDENCE_DIR'])/('allowlist-'+uuid.uuid4().hex); out.mkdir(mode=0o700)
spec=importlib.util.spec_from_file_location('relay',root/'https_relay.py'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m); os.environ['FAKE_KEY']='test-only'
checks={}; calls=[]
class FakeResponse:
 status=200
 def getheader(self,k,default=None): return 'text/event-stream'
 def read1(self,n): return b''
class Dial:
 def __init__(self,host,port,**kw): calls.append((host,port))
 def request(self,*args): pass
 def getresponse(self): return FakeResponse()
 def close(self): pass
class Sink:
 def write(self,x): return len(x)
 def flush(self): pass
class Headers(dict):
 def get_all(self,k): return [self[k]] if k in self else None
class H:
 path='/one/chat'; headers=Headers({'Host':'127.0.0.1:1234','Content-Length':'2','X-Stand-Trace-Id':'test_1'}); server=types.SimpleNamespace(server_port=1234); rfile=__import__('io').BytesIO(b'{}'); wfile=Sink()
 def send_response(self,n): pass
 def send_header(self,*a): pass
 def end_headers(self): pass
 def send_error(self,*a): raise AssertionError(a)
def args(host, index): return types.SimpleNamespace(route=['one,'+host+',/v1,FAKE_KEY'],allow_host=['api.provider.com'],test_override=None,test_ca=None,ledger=str(out/f'{index}.jsonl'),limit=25,seconds=30,timeout=2)
for i,host in enumerate(['api.provider.com.evil.example','evilapi.provider.com','API.provider.com','api.provider.com.']):
 before=len(calls)
 try: m.Relay(args(host,i)); checks['reject_'+str(i)]=False
 except ValueError: checks['reject_'+str(i)]=len(calls)==before
for i,host in enumerate(('open.bigmodel.cn','sub.lrical.com')):
 a=args(host,'real'+str(i)); a.allow_host=[host]; relay=m.Relay(a,dialer=lambda h,p,t: Dial(h,p)); relay.handle(H()); relay.ledger.close()
checks['exact_dial_two_real_hosts']=calls==[('open.bigmodel.cn',443),('sub.lrical.com',443)]
source=(root/'https_relay.py').read_text()
import ast
expected={'--route','--allow-host','--port','--ledger','--limit','--seconds','--timeout'}
def params(s):
 tree=ast.parse(s); return {n.args[0].value for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr=='add_argument' and n.args and isinstance(n.args[0],ast.Constant)}
checks['exact_cli_parameters']=params(source)==expected
checks['negative_extra_parameter_fails']=params(source.replace("p.add_argument('--route'", "p.add_argument('--test-override'); p.add_argument('--route'",1))!=expected
try:
 a=args('api.provider.com','limit'); a.limit=26; m.Relay(a); checks['limit_26_rejected']=False
except ValueError: checks['limit_26_rejected']=True
(out/'result.json').write_text(json.dumps({'checks':checks,'all':all(checks.values()),'dial_calls':calls},indent=2)+'\n'); print(out)
if not all(checks.values()): raise SystemExit(1)
