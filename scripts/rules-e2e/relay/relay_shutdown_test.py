#!/usr/bin/env python3
"""Loopback checks for bounded body reads and stopping; no external network."""
import http.client, io, math, os, pathlib, socket, subprocess, sys, tempfile, threading, time, types, unittest
from unittest.mock import patch
from relay_concurrency_test import m, args

class ShutdownTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']); self.addCleanup(self.tmp.cleanup)
  self.path=pathlib.Path(self.tmp.name)/'ledger'
  self.env=patch.dict(os.environ,{'FAKE_KEY':'fake'}); self.env.start(); self.addCleanup(self.env.stop)
 def test_positive_finite_bounds(self):
  for attr in ('seconds','timeout'):
   for value in (0,-1,float('nan'),float('inf'),float('-inf')):
    a=args(self.path); setattr(a,attr,value)
    with self.subTest(attr=attr,value=value), self.assertRaises(ValueError): m.Relay(a)
 def test_slow_upload_total_deadline(self):
  a=args(self.path); a.timeout=.3; calls=[]; relay=m.Relay(a,dialer=lambda *x:calls.append(x))
  class Handler(m.BaseHTTPRequestHandler):
   def log_message(self,*a): pass
   def do_POST(self): relay.handle(self)
  server=m.Server(('127.0.0.1',0),Handler); threading.Thread(target=server.serve_forever,daemon=True).start()
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close); self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
  c=socket.create_connection(('127.0.0.1',server.server_port),timeout=2); self.addCleanup(c.close); c.settimeout(2)
  c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: 20\r\nX-Stand-Trace-Id: slow\r\n\r\n'%server.server_port).encode())
  start=time.monotonic(); response=b''
  while time.monotonic()-start<1.2:
   try: c.sendall(b'x')
   except OSError: break
   c.settimeout(.06)
   try:
    response+=c.recv(4096)
    if response: break
   except socket.timeout: pass
  self.assertIn(b' 400 ',response); self.assertLess(time.monotonic()-start,1); self.assertEqual(relay.count,0); self.assertEqual(calls,[])
 def test_short_body_rejected(self):
  a=args(self.path); relay=m.Relay(a,dialer=lambda *x:self.fail('dial'))
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  from relay_concurrency_test import H
  h=H('short'); h.rfile=io.BytesIO(b'x'); h.send_error=lambda code,*args: setattr(h,'error',code)
  relay.handle(h); self.assertEqual(h.error,400); self.assertEqual(relay.count,0)
 def test_limit_and_deadline_stalled_inflight(self):
  code='''import importlib.util,sys,types,threading,socket,http.client,os,json
s=importlib.util.spec_from_file_location("relay",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
a=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=1 if sys.argv[3]=="limit" else 5,seconds=2,timeout=5)
r=m.Relay(a,grace=.3,abort_timeout=.1)
kind=sys.argv[4]
if kind=="dns":
 def blocked(*a): print("BLOCKED",flush=True); threading.Event().wait()
 socket.getaddrinfo=blocked
 r.dialer=lambda *a:http.client.HTTPConnection("localhost",12345,timeout=5)
elif kind=="response":
 r.dialer=lambda *a:http.client.HTTPConnection("127.0.0.1",int(sys.argv[5]),timeout=5)
elif kind=="fsync":
 def blocked(fd): print("BLOCKED",flush=True); threading.Event().wait()
 m.os.fsync=blocked
elif kind=="state-lock":
 r.condition.acquire(); print("BLOCKED",flush=True)
class H(m.BaseHTTPRequestHandler):
 def log_message(self,*a): pass
 def do_POST(self): r.handle(self)
server=m.Server(("127.0.0.1",0),H); print("PORT",server.server_port,flush=True)
if sys.argv[3]=="deadline": threading.Timer(.3,r.deadline).start()
server.serve_forever()'''
  for trigger,kind in [('limit','dns'),('deadline','dns'),('limit','response'),('deadline','response'),('limit','fsync'),('deadline','fsync'),('deadline','state-lock')]:
   with self.subTest(trigger=trigger,kind=kind), tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']) as tmp:
    up=socket.socket(); up.bind(('127.0.0.1',0)); up.listen(); up.settimeout(2)
    p=subprocess.Popen([sys.executable,'-B','-c',code,str(pathlib.Path(m.__file__)),str(pathlib.Path(tmp)/'ledger'),trigger,kind,str(up.getsockname()[1])],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake'},stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
     line=p.stdout.readline()
     if kind=='state-lock': self.assertEqual(line.strip(),'BLOCKED'); line=p.stdout.readline()
     port=int(line.split()[1]); c=socket.create_connection(('127.0.0.1',port),timeout=2); c.settimeout(2)
     c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: 2\r\nX-Stand-Trace-Id: first\r\n\r\n{}'%port).encode())
     if kind in ('dns','fsync'): self.assertEqual(p.stdout.readline().strip(),'BLOCKED')
     if kind=='response': conn,_=up.accept(); conn.settimeout(2); self.assertIn(b'POST ',conn.recv(4096))
     if trigger=='limit' and kind in ('dns','response'):
      second=http.client.HTTPConnection('127.0.0.1',port,timeout=2); second.request('POST','/one/chat',b'{}',{'X-Stand-Trace-Id':'second'}); self.assertEqual(second.getresponse().status,429); second.close()
     self.assertEqual(p.wait(timeout=3),2,p.stderr.read()); c.close()
     if kind=='response': conn.close()
    finally:
     if p.poll() is None:
      p.terminate()
      try: p.wait(timeout=2)
      except subprocess.TimeoutExpired:
       p.kill(); p.wait(timeout=2)
      self.assertIsNotNone(p.returncode)
     p.stdout.close(); p.stderr.close(); up.close()
 def test_watchdog_only_starts_once(self):
  relay=m.Relay(args(self.path),grace=1,exit_fn=lambda code:None)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  with patch.object(m.threading.Thread,'start') as start:
   relay.start_watchdog(); relay.start_watchdog(); self.assertEqual(start.call_count,1)

if __name__=='__main__': unittest.main(verbosity=2)
