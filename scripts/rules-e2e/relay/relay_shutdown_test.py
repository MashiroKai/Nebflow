#!/usr/bin/env python3
"""Loopback checks for bounded body reads and stopping; no external network."""
import fcntl, http.client, io, json, math, os, pathlib, socket, subprocess, sys, tempfile, threading, time, types, unittest
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
if sys.argv[3]=="deadline":
 def stop():
  print("DEADLINE",flush=True); r.deadline()
 threading.Timer(.3,stop).start()
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
     if kind!='state-lock':
      if trigger=='deadline': self.assertEqual(p.stdout.readline().strip(),'DEADLINE')
      ledger=pathlib.Path(tmp)/'ledger'
      records=[json.loads(line) for line in ledger.read_text().splitlines()]
      self.assertEqual(sum(x['status']=='reserved' for x in records),1)
      self.assertIsNone(p.poll()); start=time.monotonic()
      second=http.client.HTTPConnection('127.0.0.1',port,timeout=2); second.request('POST','/one/chat',b'{}',{'X-Stand-Trace-Id':'second'}); self.assertEqual(second.getresponse().status,429); second.close()
      self.assertEqual(p.wait(timeout=3),2,p.stderr.read()); self.assertGreater(time.monotonic()-start,.1)
     else: self.assertEqual(p.wait(timeout=3),2,p.stderr.read())
     c.close()
     if kind=='response': conn.close()
    finally:
     if p.poll() is None:
      p.terminate()
      try: p.wait(timeout=2)
      except subprocess.TimeoutExpired:
       p.kill(); p.wait(timeout=2)
      self.assertIsNotNone(p.returncode)
     p.stdout.close(); p.stderr.close(); up.close()
 def test_limit_hard_stop_ledger_lock(self):
  a=args(self.path,1); blocked=threading.Event(); release=threading.Event(); exited=threading.Event(); codes=[]
  relay=m.Relay(a,grace=.4,abort_timeout=.1,lock_timeout=5,exit_fn=lambda code:(codes.append(code),exited.set()))
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  from relay_concurrency_test import H
  f=open(self.path,'a'); self.addCleanup(f.close); fcntl.flock(f,fcntl.LOCK_EX)
  original=relay.event
  def event(obj):
   if obj['status']=='reserved': blocked.set()
   return original(obj)
  relay.event=event
  def work():
   try: relay.handle(H('first'))
   finally: release.set()
  worker=threading.Thread(target=work,daemon=True); worker.start(); self.assertTrue(blocked.wait(1))
  self.assertEqual(relay.count,1); self.assertTrue(relay.stopping); self.assertFalse(exited.wait(.15)); self.assertTrue(exited.wait(1)); self.assertEqual(codes,[2])
  self.assertEqual(json.loads(pathlib.Path(str(self.path)+'.abort').read_text())['status'],'aborted-on-exit')
  fcntl.flock(f,fcntl.LOCK_UN); self.assertTrue(release.wait(2)); worker.join(1)
 def test_rejection_record_before_response(self):
  relay=m.Relay(args(self.path)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  relay.stopping=True
  from relay_concurrency_test import H
  h=H('second'); observed=[]
  def send(code,*a):
   observed.append((code,[json.loads(line)['status'] for line in self.path.read_text().splitlines()]))
  h.send_error=send; relay.handle(h)
  self.assertEqual(observed,[(429,['rejected-limit'])]); self.assertEqual(relay.unrecorded_rejections,0)
 def test_unrecorded_rejection_is_bounded(self):
  relay=m.Relay(args(self.path),lock_timeout=.05,abort_timeout=.05); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  relay.stopping=True; release=threading.Event(); self.addCleanup(release.set)
  relay.event=lambda obj: release.wait(2)
  from relay_concurrency_test import H
  h=H('second'); responses=[]; h.send_error=lambda code,*a:responses.append(code)
  with patch('sys.stderr',new_callable=io.StringIO) as errors:
   start=time.monotonic(); relay.handle(h); relay.shutdown_summary(); self.assertLess(time.monotonic()-start,.5)
   self.assertIn('unrecorded rejection',errors.getvalue()); self.assertIn('"unrecorded_rejections": 1',errors.getvalue())
  self.assertEqual(responses,[429]); self.assertEqual(relay.unrecorded_rejections,1)
 def test_late_rejection_write_counterexample(self):
  # Characterize the unresolved race: timeout classification is not a durable snapshot.
  relay=m.Relay(args(self.path),lock_timeout=.05,abort_timeout=.05); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  relay.stopping=True; entered=threading.Event(); release=threading.Event(); written=threading.Event(); original=relay.event
  def delayed(obj):
   if obj['trace_id']=='late': entered.set(); release.wait(2)
   result=original(obj); written.set(); return result
  relay.event=delayed
  from relay_concurrency_test import H
  h=H('late'); h.send_error=lambda *a:None
  with patch('sys.stderr',new_callable=io.StringIO): relay.handle(h)
  self.assertTrue(entered.is_set()); self.assertEqual(relay.unrecorded_rejections,0); self.assertEqual(list(relay.rejections.values()),['inflight'])
  second=H('second'); second.send_error=lambda *a:None; relay.handle(second)
  self.assertCountEqual(relay.rejections.values(),['inflight','recorded'])
  written.clear(); release.set(); self.assertTrue(written.wait(2))
  self.assertEqual(sum(json.loads(x)['status']=='rejected-limit' for x in self.path.read_text().splitlines()),2)
  with relay.rejection_lock: self.assertEqual(relay.unrecorded_rejections,0)
 def test_late_results_after_429(self):
  from relay_concurrency_test import H
  for result in (True,False):
   with self.subTest(result=result), tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']) as tmp:
    relay=m.Relay(args(pathlib.Path(tmp)/'ledger'),lock_timeout=.03,abort_timeout=.03); relay.stopping=True
    entered=threading.Event(); release=threading.Event(); responses=[]; done=threading.Event()
    def writer(obj): entered.set(); release.wait(2); return result
    relay.event=writer; h=H('late'); h.send_error=lambda status,*a:responses.append(status)
    with patch('sys.stderr',new_callable=io.StringIO):
     relay.handle(h); self.assertTrue(entered.is_set()); self.assertEqual(responses,[429]); self.assertEqual(list(relay.rejections.values()),['inflight'])
     release.set()
     # Signal on classification rather than timing assumptions.
     def observe():
      with relay.rejection_lock:
       if list(relay.rejections.values())!=['inflight']: done.set()
      if not done.is_set(): threading.Timer(.01,observe).start()
     observe(); self.assertTrue(done.wait(2)); self.assertEqual(list(relay.rejections.values()),['recorded' if result else 'unrecorded'])
    relay.ledger.close(); relay.owner.close()
 def test_watchdog_only_starts_once(self):
  relay=m.Relay(args(self.path),grace=1,exit_fn=lambda code:None)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  with patch.object(m.threading.Thread,'start') as start:
   relay.start_watchdog(); relay.start_watchdog(); self.assertEqual(start.call_count,1)

if __name__=='__main__': unittest.main(verbosity=2)
