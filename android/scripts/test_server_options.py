"""Short loopback-only checks against the deployed service; never saturates WAN."""
import json, pathlib, signal, subprocess, time
out = pathlib.Path('/srv/rail-bandwidth-build/v02-tests')
out.mkdir(exist_ok=True)
summary = []
for name, duration, rate, stop_after in [('unlimited_rate', '1', '0', None), ('continuous', '0', '1M', 3), ('custom_61_seconds', '61', '1M', 3)]:
    cmd = ['/usr/local/bin/rail-iperf3','-c','127.0.0.1','-p','5201','-t',duration,'-b',rate,'-i','1','--json-stream','--forceflush']
    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        if stop_after is not None:
            time.sleep(stop_after)
            assert process.poll() is None, 'test was prematurely rejected or ended'
            process.send_signal(signal.SIGINT)
        stdout, stderr = process.communicate(timeout=10)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    (out / (name+'.jsonl')).write_text(stdout)
    events = [json.loads(line) for line in stdout.splitlines()]
    starts = [e['data'] for e in events if e.get('event') == 'start']
    intervals = [e['data']['sum'] for e in events if e.get('event') == 'interval']
    assert len(starts)==1 and intervals, (name,stdout,stderr)
    assert starts[0]['test_start']['duration'] == int(duration)
    assert starts[0]['target_bitrate'] == (0 if rate=='0' else 1000000)
    if stop_after is None: assert process.returncode == 0
    summary.append({'case':name,'result':'PASS','intervals':len(intervals),'loopback_only':True})
    time.sleep(.5)
(out/'summary.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary))
