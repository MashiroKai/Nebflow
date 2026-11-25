#!/usr/bin/env python3
"""Test-only constrained HTTPS relay. Not approved for production use."""
import argparse, fcntl, http.client, json, math, os, re, socket, socketserver, ssl, threading, time, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

class Server(socketserver.ThreadingMixIn, HTTPServer):
 daemon_threads = True
 allow_reuse_address = False

class Relay:
 def __init__(self, args, dialer=None, grace=60, exit_fn=os._exit, lock_timeout=5, abort_timeout=5, append_override=None):
  self.args=args; self.condition=threading.Condition(); self.lock=self.condition; self.count=0; self.seq=0
  self.grace=grace; self.exit_fn=exit_fn; self.stop_fired=False; self.finished=threading.Event(); self.watchdog_lock=threading.Lock(); self.watchdog_started=False
  self.lock_timeout=lock_timeout; self.abort_timeout=abort_timeout; self.append_override=append_override
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
  self.stopping=False; self.inflight=0; self.active={}; self.drained=threading.Event(); self.drained.set(); self.stop_callback=None
 def repair_ledger(self):
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
      self.sidecar(synthetic)  # durable intent before destructive truncation
      ledger.seek(len(b''.join(lines[:i]))); ledger.truncate()
      ledger.write((json.dumps(synthetic,separators=(',',':'))+'\n').encode()); ledger.flush(); os.fsync(ledger.fileno())
   finally: fcntl.flock(ledger.fileno(),fcntl.LOCK_UN)
 def sidecar(self,obj):
  data=(json.dumps(obj,separators=(',',':'))+'\n').encode()
  if self.append_override is not None: return self.append_override(data)
  fd=os.open(self.args.ledger+'.abort',os.O_WRONLY|os.O_CREAT|os.O_APPEND,0o600)
  try:
   if os.write(fd,data)!=len(data): raise OSError('short sidecar write')
   os.fsync(fd)
  finally: os.close(fd)
 def recover_sidecar(self):
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
      self.sidecar(obj); return
     threading.Event().wait(min(left,0.01))
   try:
    # A waiter may have opened the old inode before an atomic replacement.
    if os.fstat(ledger.fileno()).st_ino != os.stat(self.args.ledger).st_ino:
     return self.sidecar(obj)
    try:
     ledger.write(json.dumps(obj,separators=(',',':'))+'\n'); ledger.flush(); os.fsync(ledger.fileno())
    except OSError:
     self.sidecar(obj)
   finally: fcntl.flock(ledger.fileno(),fcntl.LOCK_UN)
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
  snapshot=self.active.copy()
  def record_abort():
   try:
    for seq,trace in snapshot.items():
     self.sidecar({'seq':seq,'status':'aborted-on-exit','time':time.time(),'rule_id':None,'trace_id':trace,'record_id':uuid.uuid4().hex})
   except OSError: pass
  writer=threading.Thread(target=record_abort,daemon=True); writer.start()
  writer.join(self.abort_timeout)
  self.exit_fn(2)
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
    self.count+=1; self.inflight+=1; self.active[seq]=trace[0]; self.drained.clear(); final=self.count==self.args.limit
    if final: self.stopping=True
  if final: self.start_watchdog()
  if rejected:
   self.event({'seq':seq,'status':'rejected-limit','method':'POST','host':host,'path':path,'dial':False,'time':time.time(),'rule_id':None,'trace_id':trace[0]}); h.send_error(429); return
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
 timer=threading.Timer(a.seconds,relay.deadline); timer.daemon=True; timer.start(); server.serve_forever(); timer.cancel(); server.server_close(); relay.ledger.close(); relay.owner.close()
if __name__=='__main__': main()
