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
      second=http.client.HTTPConnection('127.0.0.1',port,timeout=2)
      try:
       second.request('POST','/one/chat',b'{}',{'X-Stand-Trace-Id':'second'})
       try: self.assertEqual(second.getresponse().status,429)
       except http.client.RemoteDisconnected:
        if kind!='fsync': raise
      finally: second.close()
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
  start=time.monotonic(); relay.handle(h); self.assertLess(time.monotonic()-start,.5)
  self.assertEqual(responses,[429]); self.assertEqual(list(relay.rejections.values()),['inflight']); self.assertEqual(relay.unrecorded_rejections,0)
  release.set()
  with relay.rejection_lock: self.assertTrue(relay.rejection_lock.wait_for(lambda:list(relay.rejections.values())==['recorded'],timeout=2))
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
    entered=threading.Event(); release=threading.Event(); responses=[]
    def writer(obj): entered.set(); release.wait(2); return result
    relay.event=writer; h=H('late'); h.send_error=lambda status,*a:responses.append(status)
    with patch('sys.stderr',new_callable=io.StringIO):
     relay.handle(h); self.assertTrue(entered.is_set()); self.assertEqual(responses,[429]); self.assertEqual(list(relay.rejections.values()),['inflight'])
     release.set()
     with relay.rejection_lock:
      self.assertTrue(relay.rejection_lock.wait_for(lambda:list(relay.rejections.values())!=['inflight'],timeout=2))
      self.assertEqual(list(relay.rejections.values()),['recorded' if result else 'unrecorded'])
    relay.ledger.close(); relay.owner.close()
 def test_seal_prevents_main_and_sidecar_append(self):
  relay=m.Relay(args(self.path)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  with relay.append_lock: relay.sealed=True
  before=self.path.read_bytes()
  self.assertIs(relay.event({'seq':1,'status':'reserved'}),False)
  self.assertIs(relay.sidecar({'seq':2,'status':'reserved'}),False)
  self.assertEqual(self.path.read_bytes(),before); self.assertFalse(pathlib.Path(str(self.path)+'.abort').exists())
 def test_exit_abort_requires_seal_and_only_writes_sidecar(self):
  import hashlib
  relay=m.Relay(args(self.path)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  obj={'seq':1,'status':'aborted-on-exit','record_id':'abort-test'}
  with self.assertRaises(RuntimeError): relay.exit_abort(obj)
  digest=lambda:hashlib.sha256(self.path.read_bytes()).hexdigest()
  before=digest()
  with relay.append_lock: relay.sealed=True
  self.assertIs(relay.exit_abort(obj),True); self.assertEqual(digest(),before)
  self.assertEqual(json.loads(pathlib.Path(str(self.path)+'.abort').read_text()),obj)
 def test_hard_stop_records_pending_abort(self):
  relay=m.Relay(args(self.path),grace=.01,abort_timeout=.05,exit_fn=lambda code:codes.append(code)); codes=[]
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  entered=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  relay._event_unlocked=lambda obj:(entered.set(),release.wait(2),True)[-1]
  t=threading.Thread(target=lambda:relay.event({'seq':7,'status':'reserved','record_id':'pending'}),daemon=True); t.start(); self.assertTrue(entered.wait(1))
  with patch('sys.stderr',new_callable=io.StringIO) as out:
   relay.force_after_grace(); summaries=[json.loads(x) for x in out.getvalue().splitlines()]
  self.assertEqual(codes,[2]); self.assertTrue(summaries[0]['sealed']); self.assertEqual(summaries[1]['abort_write_confirmed'],1); self.assertEqual(summaries[1]['abort_write_unconfirmed'],0)
  self.assertTrue({'sealed','recorded_this_run','unrecorded_this_run','still_inflight_at_exit','abort_write_confirmed','abort_write_unconfirmed','inflight_upper_bound','unrecorded_rejections'}.issubset(summaries[1]))
  self.assertEqual(json.loads(pathlib.Path(str(self.path)+'.abort').read_text())['original_record_id'],'pending')
  release.set(); t.join(2)
 def test_pending_writer_finishes_during_shutdown_wait(self):
  codes=[]; relay=m.Relay(args(self.path),grace=.01,abort_timeout=1,exit_fn=codes.append)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  entered=threading.Event(); release=threading.Event(); waiting=threading.Event(); self.addCleanup(release.set)
  original_event=relay._event_unlocked; original_wait=relay.append_changed.wait_for
  def gated(obj):
   entered.set()
   if not release.wait(3): raise TimeoutError('writer gate')
   return original_event(obj)
  def signal_wait(predicate,timeout=None):
   waiting.set(); return original_wait(predicate,timeout)
  relay._event_unlocked=gated; relay.append_changed.wait_for=signal_wait
  writer=threading.Thread(target=lambda:relay.event({'seq':7,'status':'reserved','record_id':'finishes'}),daemon=True)
  writer.start(); self.assertTrue(entered.wait(1))
  with patch('sys.stderr',new_callable=io.StringIO) as out:
   stopper=threading.Thread(target=relay.force_after_grace,daemon=True); stopper.start()
   self.assertTrue(waiting.wait(2)); release.set(); writer.join(2); stopper.join(2)
   self.assertFalse(writer.is_alive()); self.assertFalse(stopper.is_alive())
   lines=[json.loads(line) for line in out.getvalue().splitlines()]
  self.assertEqual(codes,[2]); self.assertTrue(lines[0]['sealed']); summary=lines[1]
  self.assertEqual((summary['abort_write_confirmed'],summary['abort_write_unconfirmed'],summary['recorded_this_run'],summary['still_inflight_at_exit']),(0,0,1,0))
  self.assertFalse(pathlib.Path(str(self.path)+'.abort').exists())
 def test_abort_fsync_stall_is_unconfirmed_and_bounded(self):
  codes=[]; grace=.01; budget=.12
  relay=m.Relay(args(self.path),grace=grace,abort_timeout=budget,exit_fn=codes.append)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  writer_entered=threading.Event(); release_writer=threading.Event(); fsync_entered=threading.Event(); release_fsync=threading.Event(); fsync_done=threading.Event(); daemon=[]
  self.addCleanup(release_writer.set); self.addCleanup(release_fsync.set)
  relay._event_unlocked=lambda obj:(writer_entered.set(),release_writer.wait(3),True)[-1]
  original_fsync=m.os.fsync
  def stalled_fsync(fd):
   daemon.append(threading.current_thread().daemon); fsync_entered.set()
   try:
    if not release_fsync.wait(3): raise TimeoutError('fsync gate')
    return original_fsync(fd)
   finally: fsync_done.set()
  worker=threading.Thread(target=lambda:relay.event({'seq':7,'status':'reserved','record_id':'pending-fsync'}),daemon=True)
  worker.start(); self.assertTrue(writer_entered.wait(1))
  with patch.object(m.os,'fsync',side_effect=stalled_fsync),patch('sys.stderr',new_callable=io.StringIO) as out:
   start=time.monotonic(); relay.force_after_grace(); elapsed=time.monotonic()-start
   summaries=[json.loads(line) for line in out.getvalue().splitlines()]
   self.assertTrue(fsync_entered.is_set()); self.assertEqual(daemon,[True]); self.assertEqual(codes,[2])
   self.assertTrue(summaries[0]['sealed']); self.assertEqual(summaries[1]['abort_write_unconfirmed'],1); self.assertEqual(summaries[1]['abort_write_confirmed'],0)
   self.assertLessEqual(elapsed,grace+3*budget+.5)
   print('T6 elapsed_ms=%.3f budget_ms=%.3f daemon=%s exit=2 unconfirmed=1'%(elapsed*1000,(grace+3*budget+.5)*1000,daemon[0]),flush=True)
   release_fsync.set(); self.assertTrue(fsync_done.wait(2))
  release_writer.set(); worker.join(2); self.assertFalse(worker.is_alive())
 def test_admission_lock_not_held_during_io(self):
  codes=[]; relay=m.Relay(args(self.path),grace=.01,abort_timeout=.1,exit_fn=codes.append)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  entered=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  def blocked(obj): entered.set(); release.wait(3); return True
  relay._event_unlocked=blocked
  writer=threading.Thread(target=lambda:relay.event({'seq':1,'status':'reserved','record_id':'blocked'}),daemon=True)
  writer.start(); self.assertTrue(entered.wait(1))
  with patch('sys.stderr',new_callable=io.StringIO) as out:
   start=time.monotonic(); relay.force_after_grace(); elapsed=time.monotonic()-start
   lines=[json.loads(x) for x in out.getvalue().splitlines()]
  release.set(); writer.join(2)
  self.assertLess(elapsed,1); self.assertEqual(codes,[2]); self.assertTrue(lines[0]['sealed'])
 def test_writer_leaves_after_seal(self):
  relay=m.Relay(args(self.path)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  entered=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  relay._event_unlocked=lambda obj:(entered.set(),release.wait(2),True)[-1]
  writer=threading.Thread(target=lambda:relay.event({'seq':1,'status':'reserved','record_id':'leaves'}),daemon=True)
  writer.start(); self.assertTrue(entered.wait(1))
  with relay.append_lock: relay.sealed=True
  release.set(); writer.join(2); self.assertFalse(writer.is_alive())
  with relay.append_lock: self.assertNotIn('leaves',relay.append_inflight)
 def test_summary_excludes_prior_run(self):
  self.path.write_text(json.dumps({'seq':1,'status':'reserved','record_id':'old'})+'\n')
  relay=m.Relay(args(self.path,3)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  self.assertTrue(relay.event({'seq':2,'status':'reserved','record_id':'new'}))
  with patch('sys.stderr',new_callable=io.StringIO) as out: relay.shutdown_summary({})
  summary=json.loads(out.getvalue()); self.assertEqual((summary['recorded_this_run'],summary['unrecorded_this_run']),(1,0))
 def test_recovery_sealed_preserves_files(self):
  relay=m.Relay(args(self.path)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  self.path.write_bytes(b'{"seq":1'); side=pathlib.Path(str(self.path)+'.abort'); side.write_bytes(b'unchanged')
  before=(self.path.read_bytes(),side.read_bytes())
  with relay.append_lock: relay.sealed=True
  relay.repair_ledger(); relay.recover_sidecar()
  self.assertEqual((self.path.read_bytes(),side.read_bytes()),before)
 def test_repair_sealed_before_intent_preserves_tail(self):
  relay=m.Relay(args(self.path)); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  raw=b'{"seq":1,"status":"reserved"}\n{"seq":2'; self.path.write_bytes(raw)
  original=relay.sidecar
  def seal_before(obj):
   with relay.append_lock: relay.sealed=True
   return original(obj)
  relay.sidecar=seal_before
  with self.assertRaises(m.RecoveryAborted): relay.repair_ledger()
  self.assertEqual(self.path.read_bytes(),raw); self.assertFalse(pathlib.Path(str(self.path)+'.abort').exists())
 def test_recovery_inflight_hard_stop_waits(self):
  codes=[]; relay=m.Relay(args(self.path),grace=.01,abort_timeout=1,exit_fn=codes.append)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  entered=threading.Event(); release=threading.Event(); waiting=threading.Event(); self.addCleanup(release.set)
  original=relay._repair_ledger_admitted; wait=relay.append_changed.wait_for
  def gated(): entered.set(); release.wait(3); return original()
  def signal(predicate,timeout=None): waiting.set(); return wait(predicate,timeout)
  relay._repair_ledger_admitted=gated; relay.append_changed.wait_for=signal
  worker=threading.Thread(target=relay.repair_ledger,daemon=True); worker.start(); self.assertTrue(entered.wait(1))
  with patch('sys.stderr',new_callable=io.StringIO) as out:
   stopper=threading.Thread(target=relay.force_after_grace,daemon=True); stopper.start(); self.assertTrue(waiting.wait(2))
   with relay.append_lock: self.assertTrue(relay.sealed); self.assertIn('repair-ledger',relay.append_inflight.values())
   release.set(); worker.join(2); stopper.join(2)
   self.assertFalse(worker.is_alive()); self.assertFalse(stopper.is_alive()); lines=[json.loads(x) for x in out.getvalue().splitlines()]
  self.assertEqual(codes,[2]); self.assertTrue(lines[0]['sealed']); self.assertEqual(lines[1]['still_inflight_at_exit'],0)
 def test_unsealed_summary_bounds_without_io(self):
  codes=[]; relay=m.Relay(args(self.path,5),grace=0,exit_fn=codes.append); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  from relay_concurrency_test import H
  class Conn:
   sock=None
   def request(self,*a): pass
   def getresponse(self): return types.SimpleNamespace(status=200,getheader=lambda *a:'text/plain',read1=lambda n:b'')
   def close(self): pass
  relay.dialer=lambda *a:Conn(); relay.handle(H('ok'))
  with relay.lock: relay.count+=1
  held=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  def holder():
   with relay.append_lock: held.set(); release.wait(3)
  worker=threading.Thread(target=holder,daemon=True); worker.start(); self.assertTrue(held.wait(1))
  try:
   with patch.object(relay,'_ledger_record_ids',side_effect=AssertionError('must not read')),patch('sys.stderr',new_callable=io.StringIO) as out:
    start=time.monotonic(); relay.force_after_grace(); elapsed=time.monotonic()-start; raw=out.getvalue()
   summary=json.loads(raw); self.assertLess(elapsed,.5); self.assertEqual(codes,[2]); self.assertFalse(summary['sealed'])
   self.assertEqual((summary['recorded_lower_bound'],summary['unrecorded_upper_bound']),(1,1))
   self.assertEqual(summary['recorded_lower_bound']+summary['unrecorded_upper_bound'],relay.count)
   self.assertIsNone(summary['inflight']); self.assertTrue(summary['possible_extra_record_after_exit']); self.assertNotIn('流水完整',raw); self.assertNotIn('已封口',raw)
  finally: release.set(); worker.join(2)
 def test_sealed_summary_delivery_reconciliation(self):
  relay=m.Relay(args(self.path),grace=0,exit_fn=lambda code:None); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  relay.stopping=True
  from relay_concurrency_test import H
  for trace,delivered in [('delivered',True),('disconnected',False)]:
   h=H(trace)
   def send(code,*a):
    if not delivered: raise BrokenPipeError()
   h.send_error=send
   try: relay.handle(h)
   except BrokenPipeError: pass
  relay.event=lambda obj:False
  h=H('unrecorded'); h.send_error=lambda *a:None; relay.handle(h)
  with patch('sys.stderr',new_callable=io.StringIO) as out: relay.force_after_grace(); summary=json.loads(out.getvalue().splitlines()[-1])
  records=[json.loads(line) for line in self.path.read_text().splitlines()]
  self.assertTrue(summary['sealed']); self.assertEqual(len([x for x in records if x['status']=='rejected-limit']),2)
  self.assertEqual((summary['rejection_recorded_response_written'],summary['rejection_recorded_response_write_failed'],summary['rejection_unrecorded']),(1,1,1))
 def test_unsealed_snapshot_slow_fsync(self):
  codes=[]; relay=m.Relay(args(self.path,5),grace=0,exit_fn=codes.append); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  relay.count=1; self.assertTrue(relay.event({'seq':1,'status':'reserved','record_id':'one'}))
  with relay.account_lock: before=relay.confirmed_reservations; relay.count+=1
  entered=threading.Event(); release=threading.Event(); held=threading.Event(); unlock=threading.Event()
  self.addCleanup(release.set); self.addCleanup(unlock.set); original=m.os.fsync
  def slow(fd): entered.set(); release.wait(3); return original(fd)
  writer=threading.Thread(target=lambda:relay.event({'seq':2,'status':'reserved','record_id':'two'}),daemon=True)
  def holder():
   with relay.append_lock: held.set(); unlock.wait(3)
  with patch.object(m.os,'fsync',side_effect=slow):
   writer.start(); self.assertTrue(entered.wait(1)); locker=threading.Thread(target=holder,daemon=True); locker.start(); self.assertTrue(held.wait(1))
   with patch('sys.stderr',new_callable=io.StringIO) as out:
    start=time.monotonic(); relay.force_after_grace(); self.assertLess(time.monotonic()-start,.5); summary=json.loads(out.getvalue())
   self.assertEqual(codes,[2]); self.assertEqual(summary['recorded_lower_bound']+summary['unrecorded_upper_bound'],summary['reserved_snapshot'])
   self.assertGreaterEqual(summary['recorded_lower_bound'],before); self.assertTrue(summary['possible_extra_record_after_exit'])
   # The exit stub has fired and the writer is still held at fsync: for this
   # isolated interleaving the ledger is quiescent as it would be after exit.
   immediate=[json.loads(line) for line in self.path.read_text().splitlines()]
   side=pathlib.Path(str(self.path)+'.abort'); extra=[json.loads(line) for line in side.read_text().splitlines()] if side.exists() else []
   persisted=sum(x['status']=='reserved' for x in immediate+extra)
   self.assertGreaterEqual(persisted,summary['recorded_lower_bound']); self.assertLessEqual(persisted,summary['reserved_snapshot'])
   self.assertFalse(any(x['status']=='reserved' for x in extra))
   release.set(); unlock.set(); writer.join(2); locker.join(2)
  records=[json.loads(line) for line in self.path.read_text().splitlines()]; persisted=sum(x['status']=='reserved' for x in records)
  self.assertGreaterEqual(persisted,summary['recorded_lower_bound']); self.assertLessEqual(persisted,summary['reserved_snapshot'])
  with relay.account_lock: self.assertGreaterEqual(relay.confirmed_reservations,summary['recorded_lower_bound'])
 def test_unsealed_account_lock_held(self):
  codes=[]; relay=m.Relay(args(self.path),grace=0,exit_fn=codes.append); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  held=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  def holder():
   with relay.append_lock,relay.account_lock: held.set(); release.wait(3)
  t=threading.Thread(target=holder,daemon=True); t.start(); self.assertTrue(held.wait(1))
  try:
   with patch('sys.stderr',new_callable=io.StringIO) as out:
    start=time.monotonic(); relay.force_after_grace(); self.assertLess(time.monotonic()-start,.5); summary=json.loads(out.getvalue())
   self.assertFalse(summary['sealed']); self.assertEqual(codes,[2]); self.assertEqual(summary['recorded_lower_bound']+summary['unrecorded_upper_bound'],summary['reserved_snapshot'])
   self.assertEqual(summary['recorded_lower_bound'],0); self.assertEqual(self.path.read_text(),'')
  finally: release.set(); t.join(2)
 def test_account_lock_blocks_no_calls(self):
  import ast
  tree=ast.parse(pathlib.Path(m.__file__).read_text()); found=0
  for node in ast.walk(tree):
   if isinstance(node,ast.With) and any(isinstance(x.context_expr,ast.Attribute) and x.context_expr.attr=='account_lock' for x in node.items):
    found+=1; self.assertFalse(any(isinstance(x,(ast.Call,ast.With,ast.AsyncWith)) for statement in node.body for x in ast.walk(statement)))
  self.assertGreater(found,0)
 def test_rejection_concurrent_reconciliation(self):
  relay=m.Relay(args(self.path),grace=0,exit_fn=lambda code:None,lock_timeout=2); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close); relay.stopping=True
  barrier=threading.Barrier(4); from relay_concurrency_test import H
  original=relay.event
  def event(obj):
   if obj['trace_id']=='fail': return False
   return original(obj)
  relay.event=event; errors=[]
  def request(trace):
   h=H(trace)
   def send(*a):
    if trace=='broken': raise BrokenPipeError()
   h.send_error=send
   try: barrier.wait(); relay.handle(h)
   except BrokenPipeError: pass
   except Exception as exc: errors.append(exc)
  threads=[threading.Thread(target=request,args=(trace,)) for trace in ('written','broken','fail')]
  for t in threads: t.start()
  barrier.wait()
  for t in threads: t.join(3); self.assertFalse(t.is_alive())
  self.assertFalse(errors)
  with patch('sys.stderr',new_callable=io.StringIO) as out: relay.force_after_grace(); summary=json.loads(out.getvalue().splitlines()[-1])
  rows=[json.loads(x) for x in self.path.read_text().splitlines()]; ids=[x['record_id'] for x in rows]
  self.assertEqual(len(ids),len(set(ids))); self.assertEqual(set(ids),{key for key,value in relay.rejections.items() if value=='recorded'})
  self.assertEqual(len(ids),summary['rejection_recorded_response_written']+summary['rejection_recorded_response_write_failed'])
  self.assertEqual((summary['rejection_recorded_response_written'],summary['rejection_recorded_response_write_failed'],summary['rejection_unrecorded']),(1,1,1))
 def test_rejection_response_real_loopback_socket(self):
  relay=m.Relay(args(self.path),grace=0,exit_fn=lambda code:None); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close); relay.stopping=True
  ready=threading.Event(); release=threading.Event()
  class Handler(m.BaseHTTPRequestHandler):
   def log_message(self,*a): pass
   def do_POST(self):
    if self.headers['X-Stand-Trace-Id']=='closed': ready.set(); release.wait(2)
    try: relay.handle(self)
    except (BrokenPipeError,ConnectionResetError): pass
  server=m.Server(('127.0.0.1',0),Handler); worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
  self.addCleanup(server.server_close); self.addCleanup(server.shutdown); self.addCleanup(release.set)
  client=socket.create_connection(('127.0.0.1',server.server_port),timeout=2)
  request=lambda trace:('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: 2\r\nX-Stand-Trace-Id: %s\r\n\r\n{}'%(server.server_port,trace)).encode()
  client.sendall(request('closed')); self.assertTrue(ready.wait(1))
  import struct
  client.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,struct.pack('ii',1,0)); client.close(); release.set()
  with relay.rejection_lock: self.assertTrue(relay.rejection_lock.wait_for(lambda:False in relay.rejection_delivery.values(),timeout=2))
  conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=2); conn.request('POST','/one/chat',b'{}',{'X-Stand-Trace-Id':'normal'}); self.assertEqual(conn.getresponse().status,429); conn.close()
  with patch('sys.stderr',new_callable=io.StringIO) as out: relay.force_after_grace(); summary=json.loads(out.getvalue().splitlines()[-1])
  self.assertEqual((summary['rejection_recorded_response_written'],summary['rejection_recorded_response_write_failed']),(1,1))
 def test_monotonic_snapshot_random_stress(self):
  import random
  rng=random.Random(72143); relay=m.Relay(args(self.path,25),grace=0,exit_fn=lambda code:None); self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  previous=0
  for _ in range(100):
   with relay.account_lock:
    relay.count+=rng.randrange(3)
    relay.confirmed_reservations+=rng.randrange(relay.count-relay.confirmed_reservations+1)
   held=threading.Event(); release=threading.Event()
   def holder():
    with relay.append_lock: held.set(); release.wait(1)
   t=threading.Thread(target=holder); t.start(); self.assertTrue(held.wait(1))
   try:
    with patch('sys.stderr',new_callable=io.StringIO) as out: relay.force_after_grace(); summary=json.loads(out.getvalue())
    self.assertGreaterEqual(summary['recorded_lower_bound'],previous)
    self.assertEqual(summary['recorded_lower_bound']+summary['unrecorded_upper_bound'],summary['reserved_snapshot'])
    previous=summary['recorded_lower_bound']
   finally: release.set(); t.join(1)
 def matrix_slow_upload(self,trigger,lock_available):
  from relay_concurrency_test import H
  entered=threading.Event(); release=threading.Event(); uploading=threading.Event(); body_release=threading.Event(); exited=threading.Event(); codes=[]
  self.addCleanup(release.set); self.addCleanup(body_release.set)
  a=args(self.path,1 if trigger=='limit' else 5); a.seconds=.05
  def dial(*a): entered.set(); release.wait(3); raise OSError('test stop')
  relay=m.Relay(a,dialer=dial,grace=.3,abort_timeout=.1,exit_fn=lambda code:(codes.append(code),exited.set()))
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  class Handler(H):
   def send_error(self,code,*args): self.error=code
  class SlowBody:
   def read1(self,n): uploading.set(); body_release.wait(3); return b'{}'
  slow=Handler('slow'); slow.rfile=SlowBody()
  slow_thread=threading.Thread(target=relay.handle,args=(slow,),daemon=True); slow_thread.start(); self.assertTrue(uploading.wait(1))
  first=Handler('first'); start=time.monotonic(); first_thread=threading.Thread(target=relay.handle,args=(first,),daemon=True); first_thread.start(); self.assertTrue(entered.wait(1))
  self.assertEqual(relay.count,1); self.assertEqual(relay.inflight,1)
  timer=None
  if trigger=='deadline':
   timer=threading.Timer(a.seconds,relay.deadline); timer.start()
   with relay.condition: self.assertTrue(relay.condition.wait_for(lambda:relay.stopping,timeout=2))
  reject=Handler('reject'); relay.handle(reject); self.assertEqual(reject.error,429); self.assertFalse(exited.is_set())
  held=threading.Event(); unlock=threading.Event(); self.addCleanup(unlock.set); holder=None
  if not lock_available:
   def hold():
    with relay.append_lock: held.set(); unlock.wait(3)
   holder=threading.Thread(target=hold,daemon=True); holder.start(); self.assertTrue(held.wait(1))
  try:
   with patch('sys.stderr',new_callable=io.StringIO) as out:
    self.assertTrue(exited.wait(2)); lines=[json.loads(x) for x in out.getvalue().splitlines()]
   self.assertGreaterEqual(time.monotonic()-start,relay.grace); self.assertEqual(codes,[2]); summary=lines[-1]; self.assertEqual(summary['sealed'],lock_available)
   self.assertEqual(sum(json.loads(line)['status']=='reserved' for line in self.path.read_text().splitlines()),1)
   if not lock_available:
    self.assertEqual(summary['recorded_lower_bound']+summary['unrecorded_upper_bound'],summary['reserved_snapshot'])
    self.assertIsNone(summary['inflight']); self.assertTrue(summary['possible_extra_record_after_exit'])
  finally:
   unlock.set(); release.set(); body_release.set()
   if holder: holder.join(2)
   first_thread.join(2); slow_thread.join(2)
   if timer: timer.join(2)
 def test_matrix_slow_upload(self):
  for trigger in ('limit','deadline'):
   for available in (True,False):
    with self.subTest(trigger=trigger,available=available),tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']) as tmp:
     old=self.path; self.path=pathlib.Path(tmp)/'ledger'
     try: self.matrix_slow_upload(trigger,available)
     finally: self.path=old
 def test_process_limit_slow_order(self):
  self.process_fault_matrix('limit','slow',False)
 def test_process_double_trigger_once(self):
  self.process_fault_matrix('double','slow',False)
 def test_process_admission_lock_held(self):
  self.process_fault_matrix('limit','slow',True)
 def test_process_deadline_slow_free(self):
  self.process_fault_matrix('deadline','slow',False)
 def test_process_deadline_slow_locked(self):
  self.process_fault_matrix('deadline','slow',True)
 def test_process_dns_limit_free(self): self.process_fault_matrix('limit','dns',False)
 def test_process_dns_limit_locked(self): self.process_fault_matrix('limit','dns',True)
 def test_process_dns_deadline_free(self): self.process_fault_matrix('deadline','dns',False)
 def test_process_dns_deadline_locked(self): self.process_fault_matrix('deadline','dns',True)
 def test_process_halfopen_limit_free(self): self.process_fault_matrix('limit','halfopen',False)
 def test_process_halfopen_limit_locked(self): self.process_fault_matrix('limit','halfopen',True)
 def test_process_halfopen_deadline_free(self): self.process_fault_matrix('deadline','halfopen',False)
 def test_process_halfopen_deadline_locked(self): self.process_fault_matrix('deadline','halfopen',True)
 def process_fault_matrix(self,trigger,fault,lock_held):
  if fault not in ('slow','dns','halfopen'): raise ValueError('fault driver not implemented')
  if trigger not in ('limit','deadline','double'): raise ValueError('invalid trigger')
  double_trigger=trigger=='double'; deadline_first=trigger=='deadline'
  # Slow upload remains in body reading, before reservation; another complete
  # request consumes the limit and remains in flight against the loopback peer.
  # Accounting is reconciled via reservation + 429 + abort, not the
  # append-inflight field; unsealed hard stop cannot guarantee an abort.
  up=socket.socket(); up.bind(('127.0.0.1',0)); up.listen(); up.settimeout(3); self.addCleanup(up.close)
  self.assertNotEqual(up.getsockname()[1],6138); accepted=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  def provider_response(conn):
   conn.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n')
  original_provider_response=provider_response
  if fault=='halfopen':
   def provider_response(conn):
    accepted.set()
    if not release.wait(5): raise TimeoutError('halfopen release timed out')
    return original_provider_response(conn)
  def provider():
   nonlocal provider_response
   try:
    conn,_=up.accept()
    if fault=='halfopen':
     conn.settimeout(3); data=b''
     while b'\r\n\r\n' not in data:
      chunk=conn.recv(4096)
      if not chunk: raise EOFError('upstream request headers truncated')
      data+=chunk
     headers,body=data.split(b'\r\n\r\n',1)
     lengths=[int(line.split(b':',1)[1]) for line in headers.split(b'\r\n')[1:] if line.lower().startswith(b'content-length:')]
     if len(lengths)!=1: raise ValueError('expected upstream content length')
     while len(body)<lengths[0]:
      chunk=conn.recv(4096)
      if not chunk: raise EOFError('upstream request body truncated')
      body+=chunk
    if fault=='halfopen': provider_response(conn)
    else: accepted.set(); release.wait(5)
    conn.close()
   except OSError: pass
   finally: provider_response=original_provider_response
  threading.Thread(target=provider,daemon=True).start()
  code='''import sys,importlib.util,http.client,time,json,itertools\ns=importlib.util.spec_from_file_location("relay",sys.argv.pop(1)); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\nport=int(sys.argv.pop(1)); old=m.Relay.__init__; event=m.Relay.event; send=m.BaseHTTPRequestHandler.send_error; order=itertools.count()\ndef log(label): print(json.dumps({"observation":label,"seq":next(order),"monotonic":time.monotonic()}),file=sys.stderr,flush=True)\ndef init(self,args,**kw): old(self,args,dialer=lambda h,p,t:http.client.HTTPConnection("127.0.0.1",port,timeout=t),grace=1,abort_timeout=.1)\ndef observed(self,obj):\n result=event(self,obj)\n if obj.get("status")=="rejected-limit" and result is True: log("ledger-fsynced")\n return result\ndef sent(self,status,*a,**kw):\n result=send(self,status,*a,**kw)\n if status==429: log("response-written")\n return result\nm.Relay.__init__=init; m.Relay.event=observed; m.BaseHTTPRequestHandler.send_error=sent; m.main()'''
  code=code.replace('m.Relay.__init__=init; m.Relay.event=observed;', '''import threading
slow_active=threading.Event()
original_handle=m.Relay.handle
def handle_observed(self,h):
 if h.headers.get("X-Stand-Trace-Id")!="slow": return original_handle(self,h)
 slow_active.set(); log("slow-enter")
 try: return original_handle(self,h)
 finally: slow_active.clear(); log("slow-leave")
m.Relay.handle=handle_observed
force=m.Relay.force_after_grace
watchdog=m.Relay.start_watchdog
def watch_observed(self):
 import inspect
 log("watch-"+inspect.currentframe().f_back.f_code.co_name)
 return watchdog(self)
m.Relay.start_watchdog=watch_observed
original_init=init
def init(self,args,**kw):
 original_init(self,args,**kw)
 old_exit=self.exit_fn
 def exit_observed(code): log("exit-call"); old_exit(code)
 self.exit_fn=exit_observed
def force_observed(self): log("force-entry-slow-active" if slow_active.is_set() else "force-entry-slow-missing"); return force(self)
m.Relay.force_after_grace=force_observed
m.Relay.__init__=init; m.Relay.event=observed;''')
  if lock_held:
   code=code.replace('import threading\nslow_active=threading.Event()','import threading\nresponse_sent=threading.Event(); lock_held_evt=threading.Event()\nslow_active=threading.Event()')
   code=code.replace(' result=event(self,obj)\n',''' if obj.get("status")=="rejected-limit":
  log("ledger-enter")
  if response_sent.is_set() and not lock_held_evt.wait(5):
   log("lock-handshake-timeout"); raise RuntimeError("lock handshake timed out")
 result=event(self,obj)
''',1)
   code=code.replace(' old_exit=self.exit_fn',' global relay_instance\n relay_instance=self\n old_exit=self.exit_fn')
   code=code.replace(' if status==429: log("response-written")',''' if status==429:
  response_sent.set(); log("response-written")
  def hold():
   lock_held_evt.clear()
   with relay_instance.append_lock:
    lock_held_evt.set(); log("lock-held"); threading.Event().wait()
  threading.Thread(target=hold,daemon=True).start()''')
  # Deadline first rejects new requests; it cannot wake a second watchdog.
  # The positive-order double-trigger case verifies watchdog deduplication.
  read_fd,write_fd=os.pipe(); dns_read,dns_write=os.pipe(); release_read,release_write=os.pipe()
  for fd in (read_fd,write_fd,dns_read,dns_write,release_read,release_write): self.addCleanup(os.close,fd)
  if fault=='dns':
   code=code.replace(' original_init(self,args,**kw)\n',''' original_init(self,args,**kw)
 import os,socket
 resolve=socket.getaddrinfo; first=[True]
 def injected(host,*args,**kwargs):
  if host=="localhost" and first[0]:
   first[0]=False; log("dns-enter"); os.write(int(os.environ["DNS_FD"]),b"D"); os.read(int(os.environ["RELEASE_FD"]),1)
  return resolve(host,*args,**kwargs)
 socket.getaddrinfo=injected
 self.dialer=lambda h,p,t:http.client.HTTPConnection("localhost",12345,timeout=t)
''',1)
  if deadline_first:
   code=code.replace('m.Relay.start_watchdog=watch_observed','''m.Relay.start_watchdog=watch_observed
import os
old_deadline=m.Relay.deadline
def deadline_observed(self):
 old_deadline(self); log("deadline-complete"); os.write(int(os.environ["SIGNAL_FD"]),b"D")
m.Relay.deadline=deadline_observed''')
  ev=pathlib.Path(os.environ['RELAY_EVIDENCE_DIR']); prefix=ev/('round25-halfopen-'+trigger+('-locked' if lock_held else '-free')) if fault=='halfopen' else ev/('round21-dns-'+trigger+('-locked' if lock_held else '-free')) if fault=='dns' else ev/('round17-deadline-locked' if lock_held else 'round17-deadline-free') if deadline_first else ev/('round14-lock-held' if lock_held else 'round13-double-trigger' if double_trigger else 'round7-process-limit-slow'); ledger=self.path
  with open(str(prefix)+'-stderr.log','w') as err:
   p=subprocess.Popen([sys.executable,'-B','-c',code,str(pathlib.Path(m.__file__)),str(up.getsockname()[1]),'--route','one,example.com,/v1,FAKE_KEY','--allow-host','example.com','--port','0','--ledger',str(ledger),'--limit','2' if deadline_first else '1','--seconds','0.5' if double_trigger or deadline_first else '30','--timeout','5'],stdout=subprocess.PIPE,stderr=err,text=True,pass_fds=(write_fd,dns_write,release_read),env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only','SIGNAL_FD':str(write_fd),'DNS_FD':str(dns_write),'RELEASE_FD':str(release_read)})
   slow=first=None
   try:
    port=json.loads(p.stdout.readline())['port']; self.assertNotEqual(port,6138)
    def connect(trace,length,body):
     c=socket.create_connection(('127.0.0.1',port),timeout=2); c.settimeout(2); c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: %d\r\nX-Stand-Trace-Id: %s\r\n\r\n'%(port,length,trace)).encode()+body); return c
    slow=connect('slow',100,b'x'); first=connect('reserve',2,b'{}')
    if fault in ('slow','halfopen'): self.assertTrue(accepted.wait(3))
    else:
     import select
     self.assertTrue(select.select([dns_read],[],[],3)[0]); self.assertEqual(os.read(dns_read,1),b'D')
    if deadline_first:
     import select
     self.assertTrue(select.select([read_fd],[],[],3)[0]); self.assertEqual(os.read(read_fd,1),b'D')
    reject=connect('reject',2,b'{}')
    response=reject.recv(4096); stamp=time.monotonic(); reject.close(); self.assertIn(b' 429 ',response); code_exit=p.wait(timeout=4); elapsed=time.monotonic()-stamp
    pathlib.Path(str(prefix)+'-response.bin').write_bytes(response); pathlib.Path(str(prefix)+'-exit.log').write_text(str(code_exit)+'\n'); pathlib.Path(str(prefix)+'-clock.log').write_text('rejection_to_exit_seconds=%s grace_seconds=1\n'%elapsed); pathlib.Path(str(prefix)+'-ledger.log').write_text(ledger.read_text())
    side=pathlib.Path(str(ledger)+'.abort'); pathlib.Path(str(prefix)+'-sidecar.log').write_text(side.read_text() if side.exists() else '')
    self.assertEqual(code_exit,2); self.assertGreater(elapsed,.8); self.assertLess(elapsed,1.8)
    try: closed=slow.recv(1)
    except ConnectionResetError: closed=b''
    self.assertEqual(closed,b''); pathlib.Path(str(prefix)+'-slow-closed.log').write_text('EOF or reset after process exit\n')
    main_records=[json.loads(line) for line in ledger.read_text().splitlines()]; side_records=[json.loads(line) for line in side.read_text().splitlines()] if side.exists() else []
    self.assertFalse(any(x.get('trace_id')=='slow' for x in main_records+side_records))
    reservations=[x for x in main_records+side_records if x.get('status')=='reserved']; self.assertEqual(len(reservations),1); self.assertEqual(reservations[0].get('trace_id'),'reserve')
    aborts=[x for x in side_records if x.get('status')=='aborted-on-exit' and x.get('trace_id')=='reserve']; self.assertEqual(len(aborts),0 if lock_held else 1)
   finally:
    release.set()
    if slow: slow.close()
    if first: first.close()
    if p.poll() is None: p.terminate(); p.wait(timeout=3)
    p.stdout.close()
  lines=[json.loads(x) for x in pathlib.Path(str(prefix)+'-stderr.log').read_text().splitlines()]; obs=[x for x in lines if 'observation' in x]
  names=[x['observation'] for x in obs]; expected=['ledger-fsynced','response-written','slow-enter','force-entry-slow-active','exit-call']+(['dns-enter'] if fault=='dns' else [])+(['watch-deadline','deadline-complete'] if deadline_first else ['watch-handle']+(['watch-deadline'] if double_trigger else []))+(['lock-held','ledger-enter'] if lock_held else []); self.assertCountEqual(names,expected); self.assertLess(names.index('ledger-fsynced'),names.index('response-written')); self.assertLess(names.index('force-entry-slow-active'),names.index('exit-call')); self.assertEqual([x['seq'] for x in obs],list(range(len(obs)))); self.assertEqual([x['monotonic'] for x in obs],sorted(x['monotonic'] for x in obs)); self.assertEqual(lines[-2]['sealed'],not lock_held)
  if lock_held:
   summary=lines[-2]; self.assertEqual((summary['recorded_lower_bound'],summary['unrecorded_upper_bound'],summary['reserved_snapshot']),(1,0,1)); self.assertIsNone(summary['inflight']); self.assertTrue(summary['possible_extra_record_after_exit']); self.assertLess(names.index('lock-held'),names.index('exit-call'))
  else: self.assertEqual(lines[-2]['still_inflight_at_exit'],0)
  if double_trigger:
   stamps={x['observation']:x['monotonic'] for x in obs}; delta=stamps['watch-deadline']-stamps['watch-handle']; self.assertGreater(delta,0); self.assertLess(delta,1)
 def test_lf_step1_step2_fsync_probe(self): self.lf_fsync_probe(False)
 def test_lf_fsync_unsealed_probe(self): self.lf_fsync_probe(True)
 def test_lf_fsync_deadline_free(self): self.lf_fsync_probe(False,True)
 def test_lf_fsync_deadline_locked(self): self.lf_fsync_probe(True,True)
 def test_l_http_limit_free(self): self.lf_fsync_probe(False,False,True)
 def test_l_http_limit_locked(self): self.lf_fsync_probe(True,False,True)
 def test_l_http_deadline_free(self): self.lf_fsync_probe(False,True,True)
 def test_l_http_deadline_locked(self): self.lf_fsync_probe(True,True,True)
 def lf_fsync_probe(self,locked,deadline=False,ledger_lock=False):
  # Isolated process, normal A completes before the one-shot B fsync hook.
  import select
  up=socket.socket(); up.bind(('127.0.0.1',0)); up.listen(); up.settimeout(4); self.addCleanup(up.close)
  def provider():
   conn,_=up.accept(); conn.settimeout(3); data=b''
   while b'\r\n\r\n' not in data: data+=conn.recv(4096)
   headers,body=data.split(b'\r\n\r\n',1)
   length=int(next(x.split(b':',1)[1] for x in headers.split(b'\r\n') if x.lower().startswith(b'content-length:')))
   while len(body)<length: body+=conn.recv(4096)
   conn.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n'); conn.close()
  threading.Thread(target=provider,daemon=True).start()
  signal_r,signal_w=os.pipe(); command_r,command_w=os.pipe()
  for fd in (signal_r,signal_w,command_r,command_w): self.addCleanup(os.close,fd)
  code='''import sys,os,threading,importlib.util,http.client,json,time,uuid
s=importlib.util.spec_from_file_location("relay",sys.argv.pop(1)); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
port=int(sys.argv.pop(1)); old_init=m.Relay.__init__; original_append=m.Relay.append_record; original_fsync=m.os.fsync
original_print=print
def observed_print(*args,**kwargs):
 original_print(*args,**kwargs)
 if args and args[0]=='{"sealed": true}': signal({"event":"sealed","time":time.monotonic()})
 if args and isinstance(args[0],str) and args[0].startswith('{"sealed": false,'): signal({"event":"unsealed","time":time.monotonic()})
m.print=observed_print
signal_fd=int(os.environ["SIGNAL_FD"]); command_fd=int(os.environ["COMMAND_FD"])
def signal(obj): os.write(signal_fd,(json.dumps(obj)+"\\n").encode())
def append(self,obj,sidecar=False):
 obj=dict(obj); obj.setdefault("record_id",uuid.uuid4().hex)
 result=original_append(self,obj,sidecar)
 if result is True:
  with self.append_lock: inflight=obj["record_id"] in self.append_inflight
  signal({"event":"booked","record_id":obj["record_id"],"inflight":inflight,"trace":obj.get("trace_id"),"status":obj.get("status"),"time":time.monotonic()})
 return result
def init(self,args,**kw):
 old_init(self,args,dialer=lambda h,p,t:http.client.HTTPConnection("127.0.0.1",port,timeout=t),grace=3,abort_timeout=.1,lock_timeout=10)
 old_exit=self.exit_fn
 def exit_observed(code): signal({"event":"exit-call","code":code,"time":time.monotonic()}); old_exit(code)
 self.exit_fn=exit_observed
 def arm():
  os.read(command_fd,1)
  first=[True]
  def fsync(fd):
   if first[0]:
    first[0]=False; signal({"event":"fsync-enter","time":time.monotonic()}); threading.Event().wait(10)
   return original_fsync(fd)
  m.os.fsync=fsync; signal({"event":"fsync-armed","time":time.monotonic()})
  command=os.read(command_fd,1)
  if command==b"L":
   with self.append_lock:
    signal({"event":"append-lock-held","time":time.monotonic()}); threading.Event().wait(10)
 threading.Thread(target=arm,daemon=True).start()
m.Relay.__init__=init; m.Relay.append_record=append; m.main()'''
  if ledger_lock:
   code=code.replace('import sys,os,threading,importlib.util,http.client,json,time,uuid','import sys,os,threading,importlib.util,http.client,json,time,uuid,fcntl')
   code=code.replace('lock_timeout=10)', 'lock_timeout='+('10' if deadline else '.3')+')')
   old='''  first=[True]
  def fsync(fd):
   if first[0]:
    first[0]=False; signal({"event":"fsync-enter","time":time.monotonic()}); threading.Event().wait(10)
   return original_fsync(fd)
  m.os.fsync=fsync; signal({"event":"fsync-armed","time":time.monotonic()})'''
   new='''  fd=os.open(self.args.ledger,os.O_WRONLY)
  fcntl.flock(fd,fcntl.LOCK_EX)
  signal({"event":"lock-held","time":time.monotonic()})
  def heartbeat():
   while True:
    signal({"event":"lock-heartbeat","time":time.monotonic()})
    threading.Event().wait(.5)
  threading.Thread(target=heartbeat,daemon=True).start()'''
   assert old in code; code=code.replace(old,new,1)
  ev=pathlib.Path(os.environ['RELAY_EVIDENCE_DIR']); prefix=ev/('step-l-http-'+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free') if ledger_lock else 'step1-step2-fsync-probe')
  with open(str(prefix)+'-stderr.log','w') as err:
   p=subprocess.Popen([sys.executable,'-B','-c',code,str(pathlib.Path(m.__file__)),str(up.getsockname()[1]),'--route','one,example.com,/v1,FAKE_KEY','--allow-host','example.com','--port','0','--ledger',str(self.path),'--limit','3' if deadline else '2','--seconds','1' if deadline else '30','--timeout','10'],stdout=subprocess.PIPE,stderr=err,text=True,pass_fds=(signal_w,command_r),env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only','SIGNAL_FD':str(signal_w),'COMMAND_FD':str(command_r)})
   clients=[]
   try:
    port=json.loads(p.stdout.readline())['port']; self.assertNotEqual(port,6138)
    def request(trace):
     c=socket.create_connection(('127.0.0.1',port),timeout=3); c.settimeout(3); clients.append(c)
     c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: 2\r\nX-Stand-Trace-Id: %s\r\n\r\n{}'%(port,trace)).encode()); return c
    stream=os.fdopen(os.dup(signal_r),'rb',buffering=0); self.addCleanup(stream.close)
    def event():
     while True:
      self.assertTrue(select.select([stream],[],[],7)[0], 'signal timeout'); item=json.loads(stream.readline())
      if item['event']!='lock-heartbeat': return item
      if ledger_lock:
       event.last_heartbeat=item
       if not hasattr(event,'first_heartbeat'): event.first_heartbeat=item; return item
    a=request('A'); booked=event(); self.assertEqual((booked['event'],booked['trace'],booked['status']),('booked','A','reserved'))
    self.assertFalse(booked['inflight'])
    response=a.recv(4096); self.assertIn(b' 200 ',response)
    # Completion record proves A left the provider path; the parent waits for it.
    completed=event(); self.assertEqual(completed['trace'],'A')
    records=[json.loads(x) for x in self.path.read_text().splitlines()]; record=next(x for x in records if x.get('trace_id')=='A' and x.get('status')=='reserved')
    self.assertEqual(booked['record_id'],record['record_id'])
    (ev/('step1-l-normal-a.json' if ledger_lock else 'step1-normal-a.json')).write_text(json.dumps({'booked':booked,'record_id':record['record_id'],'response':response.decode('latin1'),'ledger_line':json.dumps(record)},indent=2)+'\n')
    if ledger_lock:
     (ev/('step1-l-http-'+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'booked':booked,'response':response.decode('latin1'),'record':record},indent=2)+'\n')
    os.write(command_w,b'A'); armed=event(); self.assertEqual(armed['event'],'lock-held' if ledger_lock else 'fsync-armed')
    if ledger_lock:
     heartbeat=event(); self.assertEqual(heartbeat['event'],'lock-heartbeat')
     b=request('B'); start=time.monotonic(); self.assertFalse(select.select([stream,b],[],[],.04)[0]); elapsed=time.monotonic()-start
     (ev/('step2-l-http-'+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'held':armed,'heartbeat':heartbeat,'quiet_seconds':elapsed},indent=2)+'\n')
     if not deadline:
      entered=event(); self.assertEqual((entered['event'],entered.get('trace'),entered.get('status')),('booked','B','reserved'))
     else: entered={'event':'flock-retry-observed'}
    else:
     b=request('B'); entered=event(); self.assertEqual(entered['event'],'fsync-enter')
    (ev/(('step2-l-b-enter-' if ledger_lock else 'step2-b-enter-')+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'armed':armed,'entered':entered,'B_response_ready':bool(select.select([b],[],[],0)[0])},indent=2)+'\n')
    if locked:
     os.write(command_w,b'L'); self.assertEqual(event()['event'],'append-lock-held')
    sealed_event=event(); self.assertEqual(sealed_event['event'],'unsealed' if locked else 'sealed')
    if ledger_lock:
     self.assertLessEqual(sealed_event['time']-event.last_heartbeat['time'],.65)
     (ev/('step2-l-heartbeat-'+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'initial':heartbeat,'last':event.last_heartbeat,'trigger':sealed_event,'interval_limit':.65},indent=2)+'\n')
    try:
     c=request('C'); c_result=c.recv(4096).decode('latin1')
    except (OSError,ConnectionError): c_result='disconnected'
    self.assertTrue(not c_result or c_result=='disconnected' or ' 429 ' in c_result)
    (ev/(('step3-l-c-in-window-' if ledger_lock else 'step3-f-c-in-window-')+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'C':c_result,'timing':'after sealed signal' if not locked else 'after B entered; unsealed exit not delayed'},indent=2)+'\n')
    exit_event=event(); self.assertEqual((exit_event['event'],exit_event['code']),('exit-call',2))
    exit_code=p.wait(timeout=6)
    if not ledger_lock: self.assertFalse(select.select([stream],[],[],0)[0], 'extra exit or booking signal')
    try:
     c=request('C'); c_result=c.recv(4096).decode('latin1')
    except (OSError,ConnectionError): c_result='disconnected'
    raw=self.path.read_bytes(); self.assertTrue(raw.endswith(b'\n'))
    records=[json.loads(line) for line in raw.splitlines()]
    if ledger_lock:
     self.assertEqual({r['trace_id'] for r in records if r.get('status')=='reserved'},{'A'})
     side=pathlib.Path(str(self.path)+'.abort'); side_raw=side.read_bytes() if side.exists() else b''
     self.assertTrue(not side_raw or side_raw.endswith(b'\n')); side_records=[json.loads(line) for line in side_raw.splitlines()]
     b_records=[r for r in side_records if r.get('trace_id')=='B' and r.get('status')=='reserved']
     if not deadline: self.assertEqual([r['record_id'] for r in b_records],[entered['record_id']])
     else: self.assertEqual(b_records,[])
     (ev/('step3-l-ledger-'+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'main':records,'sidecar':side_records},indent=2)+'\n')
    else: self.assertEqual({r['trace_id'] for r in records if r.get('status')=='reserved'},{'A','B'})
    self.assertFalse(any(r.get('trace_id')=='C' for r in records))
    (ev/(('step3-l-c-ledger-exit-' if ledger_lock else 'step3-f-c-ledger-exit-')+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'C':c_result,'exit_event':exit_event,'ledger':records},indent=2)+'\n')
    lines=[json.loads(line) for line in pathlib.Path(str(prefix)+'-stderr.log').read_text().splitlines() if line.startswith('{')]
    summary=next(x for x in reversed(lines) if 'recorded_lower_bound' in x or 'still_inflight_at_exit' in x)
    (ev/(('step3-l-summary-' if ledger_lock else 'step3-fsync-')+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'exit':exit_code,'summary':summary,'lock_method':'append_lock held by wrapper thread after fsync-enter' if locked else 'no injected append_lock holder','basis':'https_relay.py:274-286'},indent=2)+'\n')
    self.assertEqual(exit_code,2)
    if ledger_lock:
     (ev/('step3-l-http-'+('deadline' if deadline else 'limit')+'-'+('locked' if locked else 'free')+'.json')).write_text(json.dumps({'summary':summary,'exit':exit_code,'lock_method':'injected append_lock holder' if locked else 'no append_lock holder','basis':'https_relay.py:135-144, 160-167, 274-286'},indent=2)+'\n')
     if locked: self.assertEqual((summary['recorded_lower_bound'],summary['reserved_snapshot'],summary['unrecorded_upper_bound']),(1,2,1) if deadline else (2,2,0))
     else:
      self.assertEqual(summary['still_inflight_at_exit'],1 if deadline else 0); self.assertGreaterEqual(summary['abort_write_confirmed'],1)
    elif locked: self.assertEqual((summary['recorded_lower_bound'],summary['reserved_snapshot'],summary['unrecorded_upper_bound']),(1,2,1))
    else:
     self.assertTrue(summary['sealed']); self.assertEqual(summary['still_inflight_at_exit'],1); self.assertEqual((summary['abort_write_confirmed'],summary['abort_write_unconfirmed']),(0,0))
   finally:
    for c in clients: c.close()
    if p.poll() is None: p.terminate(); p.wait(timeout=3)
    p.stdout.close()
 def test_l_lock_step1_step2(self):
  # Lock-holder probe: A is completed before B contends on the main ledger.
  import select
  code='''import sys,os,fcntl,json,threading,time,importlib.util,uuid,types
s=importlib.util.spec_from_file_location("relay",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
a=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=30,timeout=10)
r=m.Relay(a,lock_timeout=10); original=r.append_record
def booked(obj):
 obj=dict(obj); obj.setdefault("record_id",uuid.uuid4().hex); ok=original(obj)
 print(json.dumps({"event":"booked","trace":obj["trace_id"],"record_id":obj["record_id"],"inflight":obj["record_id"] in r.append_inflight,"ok":ok}),flush=True)
booked({"seq":1,"status":"reserved","trace_id":"A"})
fd=os.open(a.ledger,os.O_WRONLY); fcntl.flock(fd,fcntl.LOCK_EX); print(json.dumps({"event":"lock-held","time":time.monotonic()}),flush=True)
threading.Thread(target=booked,args=({"seq":2,"status":"reserved","trace_id":"B"},),daemon=True).start()
os.read(0,1); fcntl.flock(fd,fcntl.LOCK_UN); os.close(fd)
'''
  ev=pathlib.Path(os.environ['RELAY_EVIDENCE_DIR']); p=subprocess.Popen([sys.executable,'-B','-c',code,str(pathlib.Path(m.__file__)),str(self.path)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'})
  try:
   self.assertTrue(select.select([p.stdout],[],[],3)[0]); a=json.loads(p.stdout.readline()); self.assertTrue(a['ok']); self.assertFalse(a['inflight'])
   self.assertTrue(select.select([p.stdout],[],[],3)[0]); held=json.loads(p.stdout.readline()); self.assertEqual(held['event'],'lock-held')
   start=time.monotonic(); self.assertFalse(select.select([p.stdout],[],[],.04)[0]); elapsed=time.monotonic()-start
   (ev/'step2-l-lock-probe.json').write_text(json.dumps({'A':a,'held':held,'no_booked_seconds':elapsed,'process_alive':p.poll() is None,'limitation':'direct append probe, no HTTP response or holder liveness handshake'},indent=2)+'\n')
  finally:
   if p.poll() is None: p.terminate(); p.wait(timeout=3)
   p.stdin.close(); p.stdout.close(); p.stderr.close()
 def test_watchdog_only_starts_once(self):
  relay=m.Relay(args(self.path),grace=1,exit_fn=lambda code:None)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  with patch.object(m.threading.Thread,'start') as start:
   relay.start_watchdog(); relay.start_watchdog(); self.assertEqual(start.call_count,1)

if __name__=='__main__': unittest.main(verbosity=2)
