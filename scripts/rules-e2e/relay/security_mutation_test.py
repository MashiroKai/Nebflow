#!/usr/bin/env python3
import pathlib,sys,tempfile,types,unittest
import relay_security_test as tests
root=pathlib.Path(__file__).parent; source=(root/'https_relay.py').read_text()
mutations={
 'r1':("h.headers.get_all('Host') != [expected_host]","False",'test_host_and_smuggling_headers_rejected'),
 'r7':("if not trace or len(trace)!=1 or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',trace[0]):","if False:",'test_trace_missing_invalid_long_no_reserve_no_forward'),
 'r10':('self.exit_fn(2)','self.exit_fn(3)','test_blocked_dns_hard_exit_reserved'),
 'r11':('self.exit_fn(2)','self.exit_fn(3)','test_network_inflight_hard_exit_closes_both_sides'),
}
old,new,test=mutations[sys.argv[1]]; assert source.count(old)==1
mutated=source.replace(old,new,1); mod=types.ModuleType('mutant'); exec(compile(mutated,'https_relay.py','exec'),mod.__dict__); tests.m=mod
print('MUTATION:',old,'=>',new,flush=True)
with tempfile.TemporaryDirectory(dir=root) as tmp:
 path=pathlib.Path(tmp)/'mutant.py'; path.write_text(mutated); tests.RELAY_PATH=path
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromName('Security.'+test,tests))
sys.exit(0 if len(result.failures)+len(result.errors)==1 else 1)
