#!/usr/bin/env python3
import http.client, importlib.util, json, os, pathlib, socket, subprocess, sys, tempfile, threading, time, types, unittest
from unittest.mock import patch
from relay_concurrency_test import ROOT,m,args
RELAY_PATH=ROOT/'https_relay.py'
class Security(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']); self.addCleanup(self.tmp.cleanup); self.path=pathlib.Path(self.tmp.name)/'ledger'; self.env=patch.dict(os.environ,{'FAKE_KEY':'fake'}); self.env.start(); self.addCleanup(self.env.stop)
 def server(self):
  calls=[]; r=m.Relay(args(self.path,10),dialer=lambda *a:calls.append(a));
  class H(m.BaseHTTPRequestHandler):
   def log_message(self,*a): pass
   def do_POST(self): r.handle(self)
  s=m.Server(('127.0.0.1',0),H); thread=threading.Thread(target=s.serve_forever,daemon=True); thread.start(); self.addCleanup(s.server_close); self.addCleanup(s.shutdown); self.addCleanup(r.owner.close); self.addCleanup(r.ledger.close); return s,r,calls
 def raw(self,s,headers):
  c=socket.create_connection(('127.0.0.1',s.server_port),timeout=2); c.sendall(b'POST /one/chat HTTP/1.1\r\n'+headers+b'\r\n\r\n{}'); response=c.recv(4096); c.close(); return response
 def test_trace_missing_invalid_long_no_reserve_no_forward(self):
  s,r,calls=self.server(); host=('Host: 127.0.0.1:%d\r\nContent-Length: 2\r\n'%s.server_port).encode()
  for trace in (b'',b'X-Stand-Trace-Id: bad!\r\n',b'X-Stand-Trace-Id: '+b'a'*65+b'\r\n'):
   before=self.path.read_text().splitlines(); self.assertIn(b' 400 ',self.raw(s,host+trace)); self.assertEqual(r.count,0); self.assertEqual(calls,[])
   records=[json.loads(x) for x in self.path.read_text().splitlines()[len(before):]]
   self.assertEqual(len(records),1); self.assertEqual((records[0]['status'],records[0]['reason']),('rejected-trace','missing-or-invalid-trace')); self.assertFalse(any(x.get('status')=='reserved' or x.get('dial') is True or x.get('status')=='forwarded' for x in records))
 def test_host_and_smuggling_headers_rejected(self):
  s,r,calls=self.server(); host=('Host: 127.0.0.1:%d\r\n'%s.server_port).encode(); common=b'Content-Length: 2\r\nX-Stand-Trace-Id: ok\r\n'
  for headers in (host+host+common,host+common+b'Transfer-Encoding: chunked\r\n',host+common+b'X-Smuggle: a\r\n b\r\n'):
   self.assertIn(b' 403 ',self.raw(s,headers)); self.assertEqual(r.count,0); self.assertEqual(calls,[])
 def child(self,kind):
  code='''import sys,types,threading,importlib.util,json,http.client,socket\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=1,timeout=5)\ndef dial(host,port,timeout):\n if sys.argv[3]=="dns": threading.Event().wait()\n return http.client.HTTPConnection("127.0.0.1",int(sys.argv[4]),timeout=timeout)\nr=m.Relay(a,dialer=dial,grace=.15,abort_timeout=.15)\nclass H(m.BaseHTTPRequestHandler):\n def log_message(self,*a): pass\n def do_POST(self): r.handle(self)\nserver=m.Server(("127.0.0.1",0),H); print(server.server_port,flush=True); threading.Timer(1.5,r.deadline).start(); server.serve_forever()'''
  env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake'}
  if kind=='network':
   up=socket.socket(); up.bind(('127.0.0.1',0)); up.listen(); up.settimeout(3); self.addCleanup(up.close); port=up.getsockname()[1]
  else: up=None; port=0
  p=subprocess.Popen([sys.executable,'-B','-c',code,str(RELAY_PATH),str(self.path),kind,str(port)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True); self.addCleanup(lambda: p.wait(timeout=4)); relayport=int(p.stdout.readline()); c=socket.create_connection(('127.0.0.1',relayport),timeout=3); c.settimeout(3); c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: 2\r\nX-Stand-Trace-Id: test\r\n\r\n{}'%relayport).encode())
  if up:
   conn,_=up.accept(); conn.settimeout(3); data=conn.recv(4096); self.assertIn(b'POST ',data)
  self.assertEqual(p.wait(timeout=3),2); self.assertEqual(c.recv(1),b''); c.close()
  if up:
   self.assertEqual(conn.recv(1),b''); conn.close()
  self.assertEqual(sum(json.loads(x).get('status')=='reserved' for x in self.path.read_text().splitlines()),1)
  p.stdout.close(); p.stderr.close()
 def test_network_inflight_hard_exit_closes_both_sides(self): self.child('network')
 def test_blocked_dns_hard_exit_reserved(self): self.child('dns')
 def test_real_getaddrinfo_hook_blocks_after_reservation(self):
  code='''import sys,types,threading,importlib.util,http.client,socket\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=1,timeout=5)\ndef blocked(*args,**kwargs): print("RESOLVER_ENTERED",flush=True); threading.Event().wait()\nsocket.getaddrinfo=blocked\nr=m.Relay(a,dialer=lambda h,p,t:http.client.HTTPConnection("localhost",12345,timeout=t),grace=.1,abort_timeout=.1)\nclass H(m.BaseHTTPRequestHandler):\n def log_message(self,*a): pass\n def do_POST(self): r.handle(self)\nserver=m.Server(("127.0.0.1",0),H); print(server.server_port,flush=True); threading.Timer(1.2,r.deadline).start(); server.serve_forever()'''
  p=subprocess.Popen([sys.executable,'-B','-c',code,str(RELAY_PATH),str(self.path)],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake'},stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True); port=int(p.stdout.readline()); c=socket.create_connection(('127.0.0.1',port),timeout=3); c.settimeout(3); c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: 2\r\nX-Stand-Trace-Id: test\r\n\r\n{}'%port).encode()); self.assertEqual(p.stdout.readline().strip(),'RESOLVER_ENTERED'); self.assertEqual(p.wait(timeout=3),2); self.assertEqual(c.recv(1),b''); c.close(); p.stdout.close(); p.stderr.close()
  self.assertEqual(sum(json.loads(x).get('status')=='reserved' for x in self.path.read_text().splitlines()),1)
if __name__=='__main__': unittest.main(verbosity=2)
