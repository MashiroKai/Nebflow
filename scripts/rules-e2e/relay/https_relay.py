#!/usr/bin/env python3
"""Test-only constrained HTTPS relay. Not approved for production use."""
import argparse, fcntl, http.client, itertools, json, math, os, re, socket, socketserver, ssl, sys, threading, time, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

class Server(socketserver.ThreadingMixIn, HTTPServer):
 daemon_threads = True
 allow_reuse_address = False

class RecoveryAborted(RuntimeError): pass

class Relay:
 def __init__(self, args, dialer=None, grace=60, exit_fn=os._exit, lock_timeout=5, abort_timeout=5, append_override=None):
  self.args=args; self.condition=threading.Condition(); self.lock=self.condition; self.count=0; self.seq=0; self.confirmed_reservations=0; self.rejection_delivery={}; self.account_lock=threading.Lock()
  self.grace=grace; self.exit_fn=exit_fn; self.stop_fired=False; self.finished=threading.Event(); self.watchdog_lock=threading.Lock(); self.watchdog_started=False
  self.lock_timeout=lock_timeout; self.abort_timeout=abort_timeout; self.append_override=append_override
  self.rejection_lock=threading.Condition(); self.unrecorded_rejections=0; self.rejections={}
  self.append_lock=threading.RLock(); self.append_changed=threading.Condition(self.append_lock); self.sidecar_lock=threading.Lock(); self.append_inflight={}; self._run_keys=set(); self._anon=itertools.count(); self.exiting=False
  with self.append_lock: self.sealed=False
  self.dialer=dialer or (lambda host,port,timeout: http.client.HTTPSConnection(host,port,timeout=timeout,context=ssl.create_default_context()))
  self.routes={}
  allowed=set(args.allow_host)
  if not allowed or any(not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*', h) for h in allowed): raise ValueError('invalid allowlist')
  for item in args.route:
   name, host, base, env = item.split(',',3)
   if not re.fullmatch(r'[A-Za-z0-9_-]+',name) or not re.fullmatch(r'[A-Za-z0-9.-]+',host) or not base.startswith('/') or '?' in base or '#' in base: raise ValueError('invalid route')
   if host not in allowed: raise ValueError('route host not allowed')
   if name in self.routes or not os.environ.get(env): raise ValueError('duplicate route or missing key')
   self.routes[name]=(host,base.rstrip('/'),os.environ[env])
  if not self.routes or not (1 <= args.limit <= 25) or not all(math.isfinite(v) and v > 0 for v in (args.seconds,args.timeout)): raise ValueError('invalid bounds')
  self.owner=open(args.ledger+'.lock','a')
  try: fcntl.flock(self.owner.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError:
   self.owner.close(); raise ValueError('ledger already locked')
  self.repair_ledger()
  self.ledger=open(args.ledger,'a+',buffering=1,encoding='utf-8')
  self.recover_sidecar()
  self.ledger.seek(0)
  seen=set()
  for line in self.ledger:
   entry=json.loads(line); self.seq=max(self.seq,entry['seq'])
   if entry.get('status')=='reserved':
    identity=entry.get('record_id')
    if identity is None or identity not in seen:
     if identity is not None: seen.add(identity)
     self.count+=1
  if self.count>=args.limit: raise ValueError('ledger exhausted')
  self.confirmed_reservations=self.count; self.rejection_delivery={}
  self.stopping=False; self.inflight=0; self.active={}; self.drained=threading.Event(); self.drained.set(); self.stop_callback=None
 def repair_ledger(self):
  leave=self._admit('repair-ledger')
  if leave is None: return
  try: return self._repair_ledger_admitted()
  finally: leave()
 def _repair_ledger_admitted(self):
  # Repair a torn tail while holding the ledger flock.
  with open(self.args.ledger,'a+b') as ledger:
   end=time.monotonic()+self.lock_timeout
   while True:
    try: fcntl.flock(ledger.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB); break
    except BlockingIOError:
     left=end-time.monotonic()
     if left<=0: raise ValueError('ledger recovery lock timeout')
     threading.Event().wait(min(left,.01))
   try:
    ledger.seek(0); raw=ledger.read(); lines=raw.splitlines(keepends=True); records=[]
    for i,line in enumerate(lines):
     try:
      if not line.endswith(b'\n'): raise ValueError('incomplete record')
      records.append(json.loads(line))
     except (ValueError,UnicodeDecodeError):
      if i!=len(lines)-1 or line.endswith(b'\n'): raise ValueError('corrupt ledger')
      seq=max([x['seq'] for x in records]+[0])+1
      synthetic={'seq':seq,'status':'reserved','record_id':'torn-main-'+__import__('hashlib').sha256(line).hexdigest()}
      if not self.sidecar(synthetic): raise RecoveryAborted('recovery intent not durable')
      ledger.seek(len(b''.join(lines[:i]))); ledger.truncate()
      ledger.write((json.dumps(synthetic,separators=(',',':'))+'\n').encode()); ledger.flush(); os.fsync(ledger.fileno())
   finally: fcntl.flock(ledger.fileno(),fcntl.LOCK_UN)
 def sidecar(self,obj):
  return self.append_record(obj,sidecar=True)
 def _sidecar_unlocked(self,obj):
  if not self.sidecar_lock.acquire(timeout=self.abort_timeout): return False
  try: return self._write_sidecar(obj)
  finally: self.sidecar_lock.release()
 def _write_sidecar(self,obj):
  data=(json.dumps(obj,separators=(',',':'))+'\n').encode()
  if self.append_override is not None: return self.append_override(data)
  fd=os.open(self.args.ledger+'.abort',os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
  try:
   if os.write(fd,data)!=len(data): raise OSError('short sidecar write')
   os.fsync(fd); return True
  finally: os.close(fd)
 def recover_sidecar(self):
  leave=self._admit('recover-sidecar')
  if leave is None: return
  try: return self._recover_sidecar_admitted()
  finally: leave()
 def _recover_sidecar_admitted(self):
  path=self.args.ledger+'.abort'
  if not os.path.exists(path): return
  # Keep the sidecar until the complete merge is fsynced. record_id makes a
  # retry after interruption idempotent, including a crash before rename.
  with open(self.args.ledger,'a+',encoding='utf-8') as ledger:
   end=time.monotonic()+self.lock_timeout
   while True:
    try:
     fcntl.flock(ledger.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB); break
    except BlockingIOError:
     left=end-time.monotonic()
     if left<=0: raise ValueError('ledger recovery lock timeout')
     threading.Event().wait(min(left,0.01))
   try:
    ledger.seek(0); ids={json.loads(line).get('record_id') for line in ledger}
    with open(path,'rb') as side: raw=side.read()
    lines=raw.splitlines(keepends=True); records=[]
    for index,line in enumerate(lines):
     try:
      if not line.endswith(b'\n'): raise ValueError('incomplete record')
      records.append(json.loads(line))
     except (ValueError,UnicodeDecodeError):
      if index!=len(lines)-1: raise ValueError('corrupt sidecar')
      # One torn append represents at most one reservation. Assign a fresh seq.
      ledger.seek(0); maxseq=max([json.loads(x)['seq'] for x in ledger]+[x['seq'] for x in records]+[0])
      records.append({'seq':maxseq+1,'status':'reserved','record_id':'torn-'+__import__('hashlib').sha256(line).hexdigest()})
    ledger.seek(0,os.SEEK_END)
    for obj in records:
     if not obj.get('record_id'): raise ValueError('invalid sidecar record')
     if obj['record_id'] not in ids:
      ledger.write(json.dumps(obj,separators=(',',':'))+'\n'); ids.add(obj['record_id'])
    ledger.flush(); os.fsync(ledger.fileno())
    os.replace(path,path+'.archived.'+uuid.uuid4().hex)
   finally: fcntl.flock(ledger.fileno(),fcntl.LOCK_UN)
 def event(self,obj):
  return self.append_record(obj)
 def _admit(self,tag,key=None,seq=None):
  with self.append_changed:
   if self.sealed: return None
   key=key if key is not None else (tag,next(self._anon))
   self.append_inflight[key]=seq if key is not None and seq is not None else tag
   if tag=='append': self._run_keys.add(key)
  def leave():
   with self.append_changed:
    self.append_inflight.pop(key,None); self.append_changed.notify_all()
  return leave
 def append_record(self,obj,sidecar=False):
  obj=dict(obj); obj.setdefault('record_id',uuid.uuid4().hex)
  leave=self._admit('append',obj['record_id'],obj.get('seq'))
  if leave is None: return False
  try:
   result=self._sidecar_unlocked(obj) if sidecar else self._event_unlocked(obj)
   if result is True and obj.get('status')=='reserved':
    with self.account_lock: self.confirmed_reservations+=1
   return result
  finally: leave()
 def _event_unlocked(self,obj):
  obj=dict(obj); obj.setdefault('record_id',uuid.uuid4().hex)
  # Separate open descriptions serialize both threads and processes.
  with open(self.args.ledger,'a',encoding='utf-8') as ledger:
   end=time.monotonic()+self.lock_timeout
   while True:
    try:
     fcntl.flock(ledger.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB); break
    except BlockingIOError:
     left=end-time.monotonic()
     if left<=0:
      return self._sidecar_unlocked(obj)
     threading.Event().wait(min(left,0.01))
   try:
    # A waiter may have opened the old inode before an atomic replacement.
    if os.fstat(ledger.fileno()).st_ino != os.stat(self.args.ledger).st_ino:
     return self._sidecar_unlocked(obj)
    try:
     data=json.dumps(obj,separators=(',',':'))+'\n'
     if ledger.write(data)!=len(data): raise OSError('short ledger write')
     ledger.flush(); os.fsync(ledger.fileno()); return True
    except OSError:
     return self._sidecar_unlocked(obj)
   finally: fcntl.flock(ledger.fileno(),fcntl.LOCK_UN)
 def rejection_event(self,obj):
  obj=dict(obj); obj.setdefault('record_id',uuid.uuid4().hex); identity=obj['record_id']
  done=threading.Event()
  with self.rejection_lock: self.rejections[identity]='inflight'; self.rejection_delivery[identity]=None
  def finalize():
   try:
    try: recorded=self.event(obj) is True
    except Exception: recorded=False
    with self.rejection_lock:
     self.rejections[identity]='recorded' if recorded else 'unrecorded'
     if not recorded: self.unrecorded_rejections+=1
     self.rejection_lock.notify_all()
    if not recorded: print('unrecorded rejection',file=sys.stderr,flush=True)
   finally: done.set()
  threading.Thread(target=finalize,daemon=True).start()
  if not done.wait(self.lock_timeout+self.abort_timeout): return False
  with self.rejection_lock: return self.rejections[identity]=='recorded'
 def rejection_response(self,h,obj):
  identity=uuid.uuid4().hex; obj['record_id']=identity
  self.rejection_event(obj)
  # Spider 裁定待作者确认：send_error 无异常仅表示响应写出，不代表客户端已接收。
  delivered=False
  try:
   h.send_error(429); delivered=True
  finally:
   with self.rejection_lock: self.rejection_delivery[identity]=delivered; self.rejection_lock.notify_all()
 def exit_abort(self,obj):
  with self.append_lock:
   if not self.sealed: raise RuntimeError('abort before sealing')
   if obj.get('status')!='aborted-on-exit': raise ValueError('invalid exit record')
   self.exiting=True
  return self._sidecar_unlocked(obj)
 def _ledger_record_ids(self):
  ids=set()
  for path in (self.args.ledger,self.args.ledger+'.abort'):
   try:
    with open(path,'rb') as f:
     for line in f:
      try:
       record=json.loads(line)
       if record.get('status')!='aborted-on-exit' and record.get('record_id'): ids.add(record['record_id'])
      except (ValueError,UnicodeDecodeError): continue
   except FileNotFoundError: pass
  return ids
 def shutdown_summary(self,pending=None,deadline=None):
  sealed=pending is not None; still={}
  deadline=deadline if deadline is not None else time.monotonic()+2*self.abort_timeout
  if sealed:
   with self.append_changed:
    self.append_changed.wait_for(lambda:not self.append_inflight,timeout=max(0,min(self.abort_timeout,(deadline-time.monotonic())/2)))
    still=dict(self.append_inflight)
  recorded=self._ledger_record_ids(); attempted=[]; results=[]; results_lock=threading.Lock()
  def abort(key,seq,trace=None):
   try: ok=self.exit_abort({'seq':seq,'status':'aborted-on-exit','record_id':uuid.uuid4().hex,'original_record_id':key,'trace_id':trace}) is True
   except Exception: ok=False
   with results_lock: results.append(ok)
  for key,seq in still.items():
   if key not in recorded:
    t=threading.Thread(target=abort,args=(key,seq),daemon=True); t.start(); attempted.append(t)
  if sealed:
   for seq,trace in self.active.copy().items():
    if seq in still.values(): continue
    t=threading.Thread(target=abort,args=(None,seq,trace),daemon=True); t.start(); attempted.append(t)
  for t in attempted: t.join(max(0,deadline-time.monotonic()))
  final=self._ledger_record_ids()
  with self.append_lock: run_keys=set(self._run_keys)
  with self.rejection_lock:
   unrecorded=self.unrecorded_rejections
   delivered=sum(state=='recorded' and self.rejection_delivery.get(key) is True for key,state in self.rejections.items())
   undelivered=sum(state=='recorded' and self.rejection_delivery.get(key) is False for key,state in self.rejections.items())
   unknown=sum(state=='inflight' or (state=='recorded' and self.rejection_delivery.get(key) is None) for key,state in self.rejections.items())
  with results_lock: confirmed=sum(results)
  summary={'sealed':sealed,'recorded_this_run':len(run_keys & final),'unrecorded_this_run':len(run_keys-final),'still_inflight_at_exit':len(still) if sealed else None,'abort_write_confirmed':confirmed,'abort_write_unconfirmed':len(attempted)-confirmed,'inflight_upper_bound':None if sealed else len(self.active),'unrecorded_rejections':unrecorded,'rejection_recorded_response_written':delivered,'rejection_recorded_response_write_failed':undelivered,'rejection_unrecorded':unrecorded,'rejection_unknown':unknown}
  print(json.dumps(summary),file=sys.stderr,flush=True)
 def maybe_stop(self):
  # Called with condition held; dispatching the callback is done by the caller.
  self.condition.notify_all()
  if self.stopping and self.inflight==0 and not self.stop_fired:
   self.stop_fired=True; self.finished.set(); return True
  return False
 def fire_stop(self,fire):
  if fire and self.stop_callback: threading.Thread(target=self.stop_callback,daemon=True).start()
 def start_watchdog(self):
  with self.watchdog_lock:
   if self.watchdog_started: return
   self.watchdog_started=True
   threading.Thread(target=self.force_after_grace,daemon=True).start()
 def deadline(self):
  self.start_watchdog()
  with self.condition:
   self.stopping=True; fire=self.maybe_stop()
  self.fire_stop(fire)
 def force_after_grace(self):
  if self.finished.wait(self.grace): return
  deadline=time.monotonic()+min(self.abort_timeout,.5)
  if self.append_lock.acquire(blocking=False):
   try:
    self.sealed=True; pending=dict(self.append_inflight)
   finally: self.append_lock.release()
   print(json.dumps({'sealed':True}),file=sys.stderr,flush=True)
  else:
   # Monotonic counters: read confirmed first, then reservations. Concurrent
   # advances can only make the remaining unrecorded upper bound conservative.
   # Neither the accounting lock nor I/O is required for these reads.
   lower=self.confirmed_reservations; reserved=self.count
   print(json.dumps({'sealed':False,'recorded_lower_bound':lower,'reserved_snapshot':reserved,'unrecorded_upper_bound':max(0,reserved-lower),'inflight':None,'possible_extra_record_after_exit':True}),file=sys.stderr,flush=True)
   self.exit_fn(2); return
  self.shutdown_summary(pending,deadline); self.exit_fn(2)
 def handle(self,h):
  start=time.monotonic(); parsed=urllib.parse.urlsplit(h.path); parts=parsed.path.split('/'); route=parts[1] if len(parts)>1 else ''; target=self.routes.get(route)
  expected_host=f'127.0.0.1:{h.server.server_port}'
  if parsed.scheme or parsed.netloc or h.headers.get_all('Host') != [expected_host] or h.headers.get('Transfer-Encoding') is not None or len(h.headers.get_all('Content-Length') or [])>1 or getattr(h.headers,'defects',[]) or any('\n' in str(v) or '\r' in str(v) for _,v in h.headers.items()) or not target or len(parts)<3 or any(x in ('.','..') for x in parts[2:]) or parsed.query or parsed.fragment:
   with self.lock:
    self.seq+=1; seq=self.seq
   self.event({'seq':seq,'status':'rejected-route','method':'POST','host':'','path':parsed.path.split('?')[0],'dial':False})
   h.send_error(403); return
  trace=h.headers.get_all('X-Stand-Trace-Id')
  if not trace or len(trace)!=1 or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',trace[0]):
   with self.lock:
    self.seq+=1; seq=self.seq
   self.event({'seq':seq,'status':'rejected-trace','reason':'missing-or-invalid-trace','rule_id':None,'trace_id':None,'time':time.time()})
   h.send_error(400); return
  host,base,key=target; path=base+'/'+('/'.join(parts[2:])); length=h.headers.get('Content-Length','')
  if not length.isdecimal() or int(length)>10485760: h.send_error(400); return
  try:
   # One monotonic budget covers the entire body, not each individual read.
   body_deadline=time.monotonic()+self.args.timeout; remaining_bytes=int(length); chunks=[]
   while remaining_bytes:
    remaining_time=body_deadline-time.monotonic()
    if remaining_time<=0: raise TimeoutError('request body deadline')
    if hasattr(h,'connection'): h.connection.settimeout(remaining_time)
    chunk=h.rfile.read1(min(remaining_bytes,65536))
    if not chunk: raise ValueError('short body')
    chunks.append(chunk); remaining_bytes-=len(chunk)
   if time.monotonic()>body_deadline: raise TimeoutError('request body deadline')
   body=b''.join(chunks)
  except (OSError,ValueError):
   h.send_error(400,'incomplete request body'); return
  finally:
   if hasattr(h,'connection'): h.connection.settimeout(None)
  final=False
  with self.lock:
   self.seq+=1; seq=self.seq
   rejected=self.stopping or self.count>=self.args.limit
   if not rejected:
    with self.account_lock: self.count+=1
    self.inflight+=1; self.active[seq]=trace[0]; self.drained.clear(); final=self.count==self.args.limit
    if final: self.stopping=True
  if final: self.start_watchdog()
  if rejected:
   self.rejection_response(h,{'seq':seq,'status':'rejected-limit','method':'POST','host':host,'path':path,'dial':False,'time':time.time(),'rule_id':None,'trace_id':trace[0]}); return
  try:
   self.event({'seq':seq,'status':'reserved','method':'POST','host':host,'path':path,'dial':False,'time':time.time(),'rule_id':None,'trace_id':trace[0]})
  except OSError:
   # Fail closed: never forward an undurable reservation or permit more traffic.
   with self.lock:
    self.stopping=True; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()
   self.fire_stop(fire)
   h.send_error(503,'ledger failure'); return
  status='error'; transferred=0; conn=None; expired=threading.Event(); deadline=time.monotonic()+self.args.timeout
  def expire():
   expired.set()
   try:
    if conn and conn.sock: conn.sock.shutdown(__import__('socket').SHUT_RDWR)
   except OSError: pass
  timer=threading.Timer(self.args.timeout,expire); timer.daemon=True; timer.start()
  try:
   conn=self.dialer(host,443,max(0.001,deadline-time.monotonic()))
   if time.monotonic()>=deadline: raise TimeoutError('deadline exceeded')
   headers={'Host':host,'Content-Type':'application/json','Authorization':'Bearer '+key,'Accept':'text/event-stream'}
   conn.request('POST',path,body,headers)
   if conn.sock: conn.sock.settimeout(max(0.001,deadline-time.monotonic()))
   response=conn.getresponse()
   if 300<=response.status<400:
    status='redirect-rejected'; h.send_error(502,'upstream redirect rejected')
   else:
    status=str(response.status); h.send_response(response.status)
    h.send_header('Content-Type',response.getheader('Content-Type','application/octet-stream')); h.end_headers()
    while True:
     remaining=deadline-time.monotonic()
     if remaining<=0: raise TimeoutError('deadline exceeded')
     if conn.sock: conn.sock.settimeout(remaining)
     chunk=response.read1(16384)
     if not chunk: break
     h.wfile.write(chunk); h.wfile.flush(); transferred+=len(chunk)
  except Exception:
   try: h.send_error(502,'upstream failure')
   except Exception: pass
  finally:
   timer.cancel()
   if expired.is_set(): status='timeout'
   if conn: conn.close()
   try:
    self.event({'seq':seq,'status':status,'method':'POST','host':host,'path':path,'elapsed_ms':int((time.monotonic()-start)*1000),'bytes':transferred,'rule_id':None,'trace_id':trace[0]})
   except OSError:
    with self.lock: self.stopping=True
   finally:
    with self.lock:
     self.inflight-=1; self.active.pop(seq,None)
     if self.inflight==0: self.drained.set()
     fire=self.maybe_stop()
    self.fire_stop(fire)

def main():
 p=argparse.ArgumentParser(); p.add_argument('--route',action='append',default=[]); p.add_argument('--allow-host',action='append',default=[]); p.add_argument('--ledger',required=True); p.add_argument('--port',type=int,default=0); p.add_argument('--limit',type=int,default=25); p.add_argument('--seconds',type=float,default=600); p.add_argument('--timeout',type=float,default=120); a=p.parse_args()
 relay=Relay(a)
 class Handler(BaseHTTPRequestHandler):
  def log_message(self,*args): pass
  def do_POST(self): relay.handle(self)
 server=Server(('127.0.0.1',a.port),Handler)
 print(json.dumps({'port':server.server_port}),flush=True)
 relay.stop_callback=server.shutdown
 timer=threading.Timer(a.seconds,relay.deadline); timer.daemon=True; timer.start(); server.serve_forever(); timer.cancel(); server.server_close(); relay.shutdown_summary(); relay.ledger.close(); relay.owner.close()
if __name__=='__main__': main()
