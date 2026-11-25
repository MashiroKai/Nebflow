#!/usr/bin/env python3
import fcntl, json, os, pathlib, subprocess, sys, threading, time, unittest
from unittest.mock import patch
from relay_concurrency_test import ROOT, m, args, H
RELAY_PATH=ROOT/'https_relay.py'

class LedgerTests(unittest.TestCase):
 def setUp(self):
  import tempfile
  self.tmp=tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']); self.addCleanup(self.tmp.cleanup); self.path=pathlib.Path(self.tmp.name)/'ledger'; self.env=patch.dict(os.environ,{'FAKE_KEY':'fake-only'}); self.env.start(); self.addCleanup(self.env.stop)
 def relay(self,**kw):
  r=m.Relay(args(self.path,3),**kw); self.addCleanup(r.ledger.close); self.addCleanup(r.owner.close); return r
 def test_lock_timeout_writes_sidecar(self):
  r=self.relay(lock_timeout=.1); f=open(self.path,'a'); self.addCleanup(f.close); fcntl.flock(f,fcntl.LOCK_EX)
  start=time.monotonic(); r.event({'seq':1,'status':'reserved'}); elapsed=time.monotonic()-start
  self.assertLess(elapsed,1); self.assertGreaterEqual(elapsed,.08); self.assertEqual(json.loads(pathlib.Path(str(self.path)+'.abort').read_text())['status'],'reserved')
 def test_restart_merges_sidecar_and_enforces_limit(self):
  r=self.relay(lock_timeout=.03); r.event({'seq':1,'status':'reserved'}); f=open(self.path,'a'); fcntl.flock(f,fcntl.LOCK_EX); r.event({'seq':2,'status':'reserved'}); f.close(); r.ledger.close(); r.owner.close()
  r2=m.Relay(args(self.path,3)); self.assertEqual(r2.count,2); self.assertEqual(len(list(self.path.parent.glob('ledger.abort.archived.*'))),1); r2.event({'seq':3,'status':'reserved'}); r2.ledger.close(); r2.owner.close()
  with self.assertRaisesRegex(ValueError,'ledger exhausted'): m.Relay(args(self.path,3))
 def child(self,stalled):
  code='''import sys,threading,types,importlib.util\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=30,timeout=5)\ndef stall(data): threading.Event().wait()\nr=m.Relay(a,grace=.05,abort_timeout=.1,append_override=stall if sys.argv[3]=="stall" else None)\nr.event({"seq":1,"status":"reserved"}); r.inflight=1; r.active[1]="trace_1"\nr.deadline(); threading.Event().wait()'''
  p=subprocess.run([sys.executable,'-B','-c',code,str(RELAY_PATH),str(self.path),'stall' if stalled else 'normal'],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'},capture_output=True,text=True,timeout=3)
  self.assertEqual(p.returncode,2,p.stderr)
 def test_abort_sidecar_written_and_recovered(self):
  self.child(False); side=pathlib.Path(str(self.path)+'.abort'); record=json.loads(side.read_text()); self.assertEqual((record['status'],record['seq'],record['trace_id']),('aborted-on-exit',1,'trace_1')); r=m.Relay(args(self.path,3)); self.assertEqual(r.count,1); r.ledger.close(); r.owner.close()
 def test_stalled_abort_writer_exits(self): self.child(True)
 def test_failed_primary_reservation_uses_durable_sidecar(self):
  r=self.relay(lock_timeout=.03); f=open(self.path,'a'); fcntl.flock(f,fcntl.LOCK_EX)
  class Response:
   status=200
   def getheader(self,k,default=None): return 'text/plain'
   def read1(self,n): return b''
  class Conn:
   sock=None
   def request(self,*a): pass
   def getresponse(self): return Response()
   def close(self): pass
  r.dialer=lambda *a: Conn(); r.handle(H('trace')); f.close()
  records=[json.loads(line) for line in pathlib.Path(str(self.path)+'.abort').read_text().splitlines()]
  self.assertEqual(r.inflight,0); self.assertEqual(r.count,1); self.assertEqual(sum(x['status']=='reserved' for x in records),1)
 def test_total_reservation_failure_stops_without_dial(self):
  r=self.relay(); calls=[]; r.dialer=lambda *a:calls.append(a)
  class ErrorHandler(H):
   def send_error(self,*a): self.error=a
  h=ErrorHandler('trace')
  with patch.object(r,'event',side_effect=OSError('injected')): r.handle(h)
  self.assertEqual((r.count,r.inflight,r.stopping),(1,0,True)); self.assertEqual(calls,[]); self.assertEqual(h.error[0],503); self.assertEqual(self.path.read_text(),'')

class RecoveryTests(unittest.TestCase):
 setUp=LedgerTests.setUp
 relay=LedgerTests.relay
 def test_recovery_lock_timeout_child(self):
  pathlib.Path(str(self.path)+'.abort').write_text('')
  f=open(self.path,'a'); fcntl.flock(f,fcntl.LOCK_EX)
  code='''import sys,types,importlib.util\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=30,timeout=5)\ntry: m.Relay(a,lock_timeout=.1)\nexcept ValueError as e: print(str(e)); sys.exit(7)\nsys.exit(0)'''
  p=subprocess.run([sys.executable,'-B','-c',code,str(RELAY_PATH),str(self.path)],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'},capture_output=True,text=True,timeout=3); f.close()
  self.assertEqual(p.returncode,7,p.stderr); self.assertIn('ledger recovery lock timeout',p.stdout)
 def test_torn_sidecar_reserves_conservatively(self):
  self.path.write_text(json.dumps({'seq':1,'status':'reserved'})+'\n')
  pathlib.Path(str(self.path)+'.abort').write_bytes(b'{"seq":2,"status":"reser')
  r=m.Relay(args(self.path,3)); self.assertEqual(r.count,2); r.ledger.close(); r.owner.close()
 def test_force_restart_limit_end_to_end(self):
  code='''import sys,threading,types,fcntl,importlib.util\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=30,timeout=5)\nr=m.Relay(a,grace=.05,abort_timeout=.3,lock_timeout=.05); r.event({"seq":1,"status":"reserved"}); f=open(a.ledger,"a"); fcntl.flock(f,fcntl.LOCK_EX); r.event({"seq":2,"status":"reserved"}); f.close(); r.inflight=1; r.active[2]="trace"; r.deadline(); threading.Event().wait()'''
  p=subprocess.run([sys.executable,'-B','-c',code,str(RELAY_PATH),str(self.path)],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'},capture_output=True,text=True,timeout=3); self.assertEqual(p.returncode,2,p.stderr)
  class Response:
   status=200
   def getheader(self,k,default=None): return 'text/plain'
   def read1(self,n): return b''
  class Conn:
   sock=None
   def request(self,*a): pass
   def getresponse(self): return Response()
   def close(self): pass
  class Handler(H):
   def send_error(self,n,*a): self.error=n
  calls=[]
  r=m.Relay(args(self.path,3),dialer=lambda *a:(calls.append(a) or Conn())); self.assertEqual(r.count,2)
  r.handle(Handler('third')); h=Handler('fourth'); r.handle(h)
  self.assertEqual((r.count,len(calls),h.error),(3,1,429)); r.ledger.close(); r.owner.close()
 def test_total_failure_restart_no_dial(self):
  r=self.relay(); calls=[]; r.dialer=lambda *a:calls.append(a)
  class Handler(H):
   def send_error(self,*a): self.error=a
  import builtins
  original_open=builtins.open; original_os_open=os.open; hits=[]
  class FailWrite:
   def __init__(self,f): self.f=f
   def __enter__(self): return self
   def __exit__(self,*a): self.f.close()
   def fileno(self): return self.f.fileno()
   def write(self,data): hits.append('main-write'); raise OSError('main injected')
  def fake_open(path,mode='r',*a,**kw):
   f=original_open(path,mode,*a,**kw)
   return FailWrite(f) if str(path)==str(self.path) and mode=='a' else f
  def fake_os_open(path,flags,*a,**kw):
   if str(path)==str(self.path)+'.abort': hits.append('sidecar-open'); raise OSError('sidecar injected')
   return original_os_open(path,flags,*a,**kw)
  with patch('builtins.open',side_effect=fake_open),patch.object(m.os,'open',side_effect=fake_os_open): r.handle(Handler('x'))
  self.assertEqual(hits,['main-write','sidecar-open']); self.assertEqual((r.count,r.inflight,r.stopping,len(calls)),(1,0,True,0)); r.ledger.close(); r.owner.close()
  r2=m.Relay(args(self.path,3)); self.assertEqual(r2.count,0); r2.ledger.close(); r2.owner.close()
 def test_torn_main_conservative_and_middle_corruption_rejected(self):
  self.path.write_bytes(b'{"seq":1,"status":"reserved"}\n{"seq":2,"status":"res')
  r=m.Relay(args(self.path,3)); self.assertEqual(r.count,2); r.ledger.close(); r.owner.close()
  self.path.write_bytes(b'{bad}\n{"seq":2,"status":"reserved"}\n')
  with self.assertRaisesRegex(ValueError,'corrupt ledger'): m.Relay(args(self.path,3))
 def test_torn_main_and_sidecar_same_seq_both_count(self):
  self.path.write_bytes(b'{"seq":1,"status":"res')
  pathlib.Path(str(self.path)+'.abort').write_text(json.dumps({'seq':1,'status':'reserved','record_id':'side-one'})+'\n')
  r=m.Relay(args(self.path,3)); self.assertEqual(r.count,2); r.ledger.close(); r.owner.close()
  r2=m.Relay(args(self.path,3)); self.assertEqual(r2.count,2); r2.ledger.close(); r2.owner.close()
 def test_fsync_uncertain_failure_fails_closed(self):
  r=self.relay(); calls=[]; r.dialer=lambda *a:calls.append(a)
  class Handler(H):
   def send_error(self,*a): self.error=a
  original=m.os.fsync; attempts=[]
  def bad(fd): attempts.append(fd); raise OSError('fsync uncertain')
  with patch.object(m.os,'fsync',side_effect=bad): r.handle(Handler('trace'))
  self.assertEqual((r.count,r.inflight,r.stopping,len(calls)),(1,0,True,0)); self.assertEqual(len(attempts),2); r.ledger.close(); r.owner.close()
  r2=m.Relay(args(self.path,3)); self.assertGreaterEqual(r2.count,len(calls)); r2.ledger.close(); r2.owner.close()
 def test_replaced_inode_waiter_uses_sidecar(self):
  r=self.relay(lock_timeout=1); original=open(self.path,'a'); fcntl.flock(original,fcntl.LOCK_EX)
  entered=threading.Event(); real=m.fcntl.flock
  def observe(fd,flags):
   if flags & fcntl.LOCK_NB: entered.set()
   return real(fd,flags)
  with patch.object(m.fcntl,'flock',side_effect=observe):
   t=threading.Thread(target=r.event,args=({'seq':1,'status':'reserved','record_id':'append'},)); t.start(); self.assertTrue(entered.wait(2))
   replacement=self.path.with_name('replacement'); replacement.write_text(''); os.replace(replacement,self.path); original.close(); t.join(2); self.assertFalse(t.is_alive())
  side=pathlib.Path(str(self.path)+'.abort'); self.assertEqual(json.loads(side.read_text())['record_id'],'append'); r.ledger.close(); r.owner.close()
  r2=m.Relay(args(self.path,3)); self.assertEqual(r2.count,1); r2.ledger.close(); r2.owner.close()
 def test_repair_crash_before_and_after_truncate(self):
  code='''import sys,types,importlib.util,os\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=5,seconds=30,timeout=5)\nif sys.argv[3]=="before":\n old=m.Relay.sidecar\n def stop(self,obj): old(self,obj); os._exit(71)\n m.Relay.sidecar=stop\nelse:\n old=os.fsync\n def stop(fd):\n  if os.path.exists(sys.argv[2]+".abort") and os.stat(sys.argv[2]).st_size==0: os._exit(72)\n  old(fd)\n # truncate interception instead of fsync to hit the exact window\n import builtins\n op=builtins.open\n class Wrap:\n  def __init__(self,f): self.f=f\n  def __enter__(self): return self\n  def __exit__(self,*a): self.f.close()\n  def __getattr__(self,n): return getattr(self.f,n)\n  def truncate(self,*a): self.f.truncate(*a); os._exit(72)\n def opened(path,mode="r",*a,**k):\n  f=op(path,mode,*a,**k); return Wrap(f) if str(path)==sys.argv[2] and mode=="a+b" else f\n builtins.open=opened\nm.Relay(a)'''
  for point,exitcode in (('before',71),('after',72)):
   with self.subTest(point=point):
    path=self.path.with_name('ledger-'+point); path.write_bytes(b'{"seq":1,"status":"res')
    p=subprocess.run([sys.executable,'-B','-c',code,str(RELAY_PATH),str(path),point],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'},capture_output=True,text=True,timeout=3); self.assertEqual(p.returncode,exitcode,p.stderr)
    r=m.Relay(args(path,5)); self.assertGreaterEqual(r.count,1); r.ledger.close(); r.owner.close()
 def test_merge_crash_idempotent_restart(self):
  self.path.write_text(json.dumps({'seq':1,'status':'reserved','record_id':'one'})+'\n')
  side=pathlib.Path(str(self.path)+'.abort'); side.write_text(json.dumps({'seq':2,'status':'reserved','record_id':'two'})+'\n')
  code='''import sys,os,types,importlib.util\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=3,seconds=30,timeout=5)\nm.os.replace=lambda *args:os._exit(73)\nm.Relay(a)'''
  p=subprocess.run([sys.executable,'-B','-c',code,str(RELAY_PATH),str(self.path)],env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'},capture_output=True,text=True,timeout=3); self.assertEqual(p.returncode,73,p.stderr); self.assertTrue(side.exists())
  r=m.Relay(args(self.path,3)); self.assertEqual(r.count,2); self.assertEqual(sum(json.loads(x).get('record_id')=='two' for x in self.path.read_text().splitlines()),1); r.ledger.close(); r.owner.close()

if __name__=='__main__': unittest.main(verbosity=2)
