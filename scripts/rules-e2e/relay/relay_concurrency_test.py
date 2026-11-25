#!/usr/bin/env python3
import importlib.util, io, os, pathlib, subprocess, sys, tempfile, threading, types, unittest
from unittest.mock import patch

ROOT=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('relay',ROOT/'https_relay.py')
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

def args(path,limit=2):
 return types.SimpleNamespace(route=['one,example.com,/v1,FAKE_KEY'],allow_host=['example.com'],ledger=str(path),limit=limit,seconds=30,timeout=5)

class H:
 def __init__(self,trace):
  class Headers(dict):
   def get_all(self,k): return [self[k]] if k in self else None
  self.path='/one/chat'; self.headers=Headers({'Host':'127.0.0.1:1234','Content-Length':'2','X-Stand-Trace-Id':trace}); self.server=types.SimpleNamespace(server_port=1234); self.rfile=io.BytesIO(b'{}'); self.wfile=io.BytesIO()
 def send_response(self,n): pass
 def send_header(self,*a): pass
 def end_headers(self): pass
 def send_error(self,*a): raise AssertionError(a)

class Tests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=os.environ['RELAY_EVIDENCE_DIR']); self.addCleanup(self.tmp.cleanup); self.path=pathlib.Path(self.tmp.name)/'ledger'; self.env=patch.dict(os.environ,{'FAKE_KEY':'fake-only'}); self.env.start(); self.addCleanup(self.env.stop)
 def test_inflight_limit_stops_once(self):
  entered=[threading.Event(),threading.Event()]; release=[threading.Event(),threading.Event()]; lock=threading.Lock(); indexes=iter(range(2)); stopped=threading.Event(); calls=[]
  class Response:
   status=200
   def getheader(self,k,default=None): return 'text/plain'
   def read1(self,n): return b''
  class Conn:
   sock=None
   def __init__(self,i): self.i=i
   def request(self,*a): entered[self.i].set(); self.ok=release[self.i].wait(3)
   def getresponse(self):
    if not self.ok: raise RuntimeError('release timeout')
    return Response()
   def close(self): pass
  def dial(*a):
   with lock: i=next(indexes)
   return Conn(i)
  relay=m.Relay(args(self.path),dialer=dial); relay.stop_callback=lambda:(calls.append('stop'),stopped.set())
  first=threading.Thread(target=relay.handle,args=(H('first'),)); first.start(); self.assertTrue(entered[0].wait(3))
  second=threading.Thread(target=relay.handle,args=(H('second'),)); second.start(); self.assertTrue(entered[1].wait(3))
  release[1].set(); second.join(3); self.assertFalse(second.is_alive()); self.assertFalse(stopped.is_set()); self.assertEqual(relay.inflight,1)
  release[0].set(); first.join(3); self.assertFalse(first.is_alive()); self.assertTrue(stopped.wait(3)); relay.deadline(); self.assertEqual(calls,['stop']); relay.ledger.close(); relay.owner.close()
 def test_force_exit_with_stalled_ledger_holder(self):
  # The child holds the actual flock indefinitely in a daemon thread, while another
  # daemon attempts an append. Deadline must terminate the child without either lock.
  code='''import sys,os,threading,fcntl,types,importlib.util\ns=importlib.util.spec_from_file_location("r",sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)\na=types.SimpleNamespace(route=["one,example.com,/v1,FAKE_KEY"],allow_host=["example.com"],ledger=sys.argv[2],limit=2,seconds=30,timeout=5)\nr=m.Relay(a,grace=0.15); held=threading.Event()\ndef holder():\n f=open(a.ledger,"a"); fcntl.flock(f.fileno(),fcntl.LOCK_EX); held.set(); threading.Event().wait()\nthreading.Thread(target=holder,daemon=True).start(); assert held.wait(2)\nwith r.condition: r.inflight=1; r.active[1]="trace"; r.drained.clear()\nthreading.Thread(target=lambda:r.event({"seq":1}),daemon=True).start()\nprint("LOCKED",flush=True); r.deadline(); threading.Event().wait()'''
  env={'PATH':'/usr/bin:/bin','FAKE_KEY':'fake-only'}
  p=subprocess.run([sys.executable,'-B','-c',code,str(ROOT/'https_relay.py'),str(self.path)],env=env,capture_output=True,text=True,timeout=3)
  self.assertEqual(p.stdout.strip(),'LOCKED'); self.assertEqual(p.returncode,2,p.stderr)
 def test_lifetime_lock_and_restart(self):
  first=m.Relay(args(self.path)); self.addCleanup(first.ledger.close); self.addCleanup(first.owner.close)
  with self.assertRaisesRegex(ValueError,'ledger already locked'): m.Relay(args(self.path))
  first.ledger.close(); first.owner.close(); second=m.Relay(args(self.path)); second.ledger.close(); second.owner.close()

if __name__=='__main__': unittest.main(verbosity=2)
