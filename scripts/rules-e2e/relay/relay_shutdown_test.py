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
  self.process_limit_slow_order(False)
 def test_process_double_trigger_once(self):
  self.process_limit_slow_order(True)
 def process_limit_slow_order(self,double_trigger):
  # Slow upload remains in body reading, before reservation; another complete
  # request consumes the limit and remains in flight against the loopback peer.
  up=socket.socket(); up.bind(('127.0.0.1',0)); up.listen(); up.settimeout(3); self.addCleanup(up.close)
  self.assertNotEqual(up.getsockname()[1],6138); accepted=threading.Event(); release=threading.Event(); self.addCleanup(release.set)
  def provider():
   try:
    conn,_=up.accept(); accepted.set(); release.wait(5); conn.close()
   except OSError: pass
  threading.Thread(target=provider,daemon=True).start()
  code='''import sys,importlib.util,http.client,time,json,itertools\ns=importlib.util.spec_from_file_location("relay",sys.argv.pop(1)); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\nport=int(sys.argv.pop(1)); old=m.Relay.__init__; event=m.Relay.event; send=m.BaseHTTPRequestHandler.send_error; order=itertools.count()\ndef log(label): print(json.dumps({"observation":label,"seq":next(order),"monotonic":time.monotonic()}),file=sys.stderr,flush=True)\ndef init(self,args,**kw): old(self,args,dialer=lambda h,p,t:http.client.HTTPConnection("127.0.0.1",port,timeout=t),grace=1,abort_timeout=.1)\ndef observed(self,obj):\n result=event(self,obj)\n if obj.get("status")=="rejected-limit" and result is True: log("ledger-fsynced")\n return result\ndef sent(self,status,*a,**kw):\n result=send(self,status,*a,**kw)\n if status==429: log("response-written")\n return result\nm.Relay.__init__=init; m.Relay.event=observed; m.BaseHTTPRequestHandler.send_error=sent; m.main()'''
  code=code.replace('m.Relay.__init__=init; m.Relay.event=observed;', '''force=m.Relay.force_after_grace
original_init=init
def init(self,args,**kw):
 original_init(self,args,**kw)
 old_exit=self.exit_fn
 def exit_observed(code): log("exit-call"); old_exit(code)
 self.exit_fn=exit_observed
def force_observed(self): log("force-entry"); return force(self)
m.Relay.force_after_grace=force_observed
m.Relay.__init__=init; m.Relay.event=observed;''')
  ev=pathlib.Path(os.environ['RELAY_EVIDENCE_DIR']); prefix=ev/('round13-double-trigger' if double_trigger else 'round7-process-limit-slow'); ledger=self.path
  with open(str(prefix)+'-stderr.log','w') as err:
   p=subprocess.Popen([sys.executable,'-B','-c',code,str(pathlib.Path(m.__file__)),str(up.getsockname()[1]),'--route','one,example.com,/v1,FAKE_KEY','--allow-host','example.com','--port','0','--ledger',str(ledger),'--limit','1','--seconds','0.5' if double_trigger else '30','--timeout','5'],stdout=subprocess.PIPE,stderr=err,text=True,env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'})
   slow=first=None
   try:
    port=json.loads(p.stdout.readline())['port']; self.assertNotEqual(port,6138)
    def connect(trace,length,body):
     c=socket.create_connection(('127.0.0.1',port),timeout=2); c.settimeout(2); c.sendall(('POST /one/chat HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nContent-Length: %d\r\nX-Stand-Trace-Id: %s\r\n\r\n'%(port,length,trace)).encode()+body); return c
    slow=connect('slow',100,b'x'); first=connect('reserve',2,b'{}'); self.assertTrue(accepted.wait(3)); reject=connect('reject',2,b'{}')
    response=reject.recv(4096); stamp=time.monotonic(); reject.close(); self.assertIn(b' 429 ',response); code_exit=p.wait(timeout=4); elapsed=time.monotonic()-stamp
    pathlib.Path(str(prefix)+'-response.bin').write_bytes(response); pathlib.Path(str(prefix)+'-exit.log').write_text(str(code_exit)+'\n'); pathlib.Path(str(prefix)+'-clock.log').write_text('rejection_to_exit_seconds=%s grace_seconds=1\n'%elapsed); pathlib.Path(str(prefix)+'-ledger.log').write_text(ledger.read_text())
    side=pathlib.Path(str(ledger)+'.abort'); pathlib.Path(str(prefix)+'-sidecar.log').write_text(side.read_text() if side.exists() else '')
    self.assertEqual(code_exit,2); self.assertGreater(elapsed,.8)
   finally:
    release.set()
    if slow: slow.close()
    if first: first.close()
    if p.poll() is None: p.terminate(); p.wait(timeout=3)
    p.stdout.close()
  lines=[json.loads(x) for x in pathlib.Path(str(prefix)+'-stderr.log').read_text().splitlines()]; obs=[x for x in lines if 'observation' in x]
  names=[x['observation'] for x in obs]; self.assertCountEqual(names,['ledger-fsynced','response-written','force-entry','exit-call']); self.assertLess(names.index('ledger-fsynced'),names.index('response-written')); self.assertLess(names.index('force-entry'),names.index('exit-call')); self.assertEqual([x['seq'] for x in obs],list(range(4))); self.assertEqual([x['monotonic'] for x in obs],sorted(x['monotonic'] for x in obs)); self.assertTrue(lines[-2]['sealed'])
 def test_watchdog_only_starts_once(self):
  relay=m.Relay(args(self.path),grace=1,exit_fn=lambda code:None)
  self.addCleanup(relay.owner.close); self.addCleanup(relay.ledger.close)
  with patch.object(m.threading.Thread,'start') as start:
   relay.start_watchdog(); relay.start_watchdog(); self.assertEqual(start.call_count,1)

if __name__=='__main__': unittest.main(verbosity=2)
