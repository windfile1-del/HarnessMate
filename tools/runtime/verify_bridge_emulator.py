"""Real Bridge smoke on fixed isolated API31/35 task emulators; never targets a handset or Harness APK."""
import argparse, hashlib, hmac, json, os, pathlib, socket, struct, subprocess, threading, time, uuid, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(); parser.add_argument('--api',type=int,choices=(31,35),default=35)
parser.add_argument('--layer-probe',action='store_true')
parser.add_argument('--permission-regression',action='store_true')
parser.add_argument('--lifecycle-only',action='store_true')
parser.add_argument('--redirect-only',action='store_true')
parser.add_argument('--retained-task-only',action='store_true')
parser.add_argument('--variant',choices=('default','gpu-off'),default='default')
OPTIONS=parser.parse_args(); API=OPTIONS.api
BUILD = ROOT / '.build-virtual-display' / ('emulator-proof-r12-api'+str(API))
if OPTIONS.variant!='default': BUILD=BUILD.with_name(BUILD.name+'-'+OPTIONS.variant)
if OPTIONS.permission_regression: BUILD=BUILD.with_name(BUILD.name+'-permission')
if OPTIONS.lifecycle_only: BUILD=BUILD.with_name(BUILD.name+'-lifecycle')
if OPTIONS.redirect_only: BUILD=BUILD.with_name(BUILD.name+'-redirect')
if OPTIONS.retained_task_only: BUILD=BUILD.with_name(BUILD.name+'-retained-task')
BUILD.mkdir(parents=True, exist_ok=True)
ADB = r'D:\HTML\android-sdk\platform-tools\adb.exe'
SERIAL,PORT = ('emulator-5560',5060) if API==31 else ('emulator-5558',5058)
JAVA = pathlib.Path(r'D:\VSCode\bin')
SDK = ROOT / 'assets/embedded/android-35/android.jar'
TOOLS = pathlib.Path(r'D:\HTML\android-sdk\build-tools\34.0.0')
FIXTURE = ROOT / 'device-tests/virtual-display-fixture'
report = {'scope': 'BRIDGE_API'+str(API)+'_EMULATOR', 'feature_revision':12,'physical_device': 'NOT_RUN', 'harness_apk': 'NOT_PACKAGED', 'checks': []}
report['test_variant']=OPTIONS.variant

def adb(*args):
    return subprocess.check_output([ADB, '-P', str(PORT), '-s', SERIAL, *args], timeout=30).decode('utf-8', 'replace').strip()

def record(name, value):
    report['checks'].append({'name': name, 'result': value})
    (BUILD / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print(name + ': ' + json.dumps(value, ensure_ascii=False), flush=True)

def require(value, name):
    if not value:
        raise AssertionError(name)

def exact(sock, length):
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk: raise EOFError('ADB_HOST_SOCKET_CLOSED')
        data.extend(chunk)
    return bytes(data)

def service(sock, value):
    data = value.encode()
    sock.sendall(('%04x' % len(data)).encode() + data)
    status = exact(sock, 4)
    if status != b'OKAY':
        size = int(exact(sock, 4), 16)
        exact(sock, size)
        raise RuntimeError('ADB_HOST_SERVICE_REJECTED')

class Pipe:
    def __init__(self, command):
        self.socket = socket.create_connection(('127.0.0.1', PORT), timeout=12)
        service(self.socket, 'host:transport:' + SERIAL)
        service(self.socket, 'shell,v2,raw:' + command)
        self.pending = bytearray()
    def write(self, value):
        self.socket.sendall(struct.pack('<BI', 0, len(value)) + value)
    def read(self, length):
        while len(self.pending) < length:
            stream, size = struct.unpack('<BI', exact(self.socket, 5))
            require(stream in (1, 2, 3) and size <= 1048576, 'shell frame bounds')
            value = exact(self.socket, size)
            if stream == 1: self.pending.extend(value)
            elif stream == 3: raise EOFError('BRIDGE_PROCESS_EXITED_' + str(value[0]))
            # stderr deliberately discarded, never mixed with binary Bridge frames.
        value = bytes(self.pending[:length]); del self.pending[:length]
        return value
    def frame(self):
        length = struct.unpack('>I', self.read(4))[0]
        require(0 < length <= 16777216, 'bridge frame bounds')
        return self.read(length)
    def send(self, value):
        data = json.dumps(value, separators=(',', ':')).encode()
        self.write(struct.pack('>I', len(data)) + data)
    def auth(self, channel, session, secret):
        challenge = json.loads(self.frame())
        if challenge.get('status') == 'START_FAILED':
            record('startup_failure', challenge)
            raise RuntimeError(challenge.get('error'))
        require(challenge['session'] == session and challenge['version'] == 2, 'handshake identity')
        proof = hmac.new(secret.encode(), (session + ':' + challenge['challenge'] + ':' + channel).encode(), hashlib.sha256).hexdigest()
        self.send({'proof': proof})
        require(json.loads(self.frame())['status'] == 'AUTHENTICATED', 'authenticated')
    def rpc(self, method, args, session, request=None):
        request = request or str(uuid.uuid4())
        self.send({'version': 2, 'session': session, 'request_id': request, 'method': method, 'args': args})
        response = json.loads(self.frame())
        require(response['request_id'] == request and response['version'] == 2, 'response identity')
        return response
    def close(self): self.socket.close()

def build_fixture():
    classes = BUILD / 'classes'; classes.mkdir(exist_ok=True)
    subprocess.run([str(JAVA / 'javac.exe'), '--release', '8', '-classpath', str(SDK), '-d', str(classes), str(FIXTURE / 'MainActivity.java')], check=True)
    archive = BUILD / 'fixture-classes.jar'
    with zipfile.ZipFile(archive, 'w') as z:
        for path in classes.rglob('*.class'): z.write(path, path.relative_to(classes).as_posix())
    subprocess.run([str(JAVA / 'java.exe'), '-cp', str(ROOT / 'third_party/embedded/r8/r8-8.2.33.jar'), 'com.android.tools.r8.D8', '--min-api', '30', '--lib', str(SDK), '--output', str(BUILD), str(archive)], check=True)
    unsigned = BUILD / 'fixture-unsigned.apk'
    subprocess.run([str(TOOLS / 'aapt2.exe'), 'link', '-I', str(SDK), '--manifest', str(FIXTURE / 'AndroidManifest.xml'), '-o', str(unsigned)], check=True)
    with zipfile.ZipFile(unsigned, 'a') as z: z.write(BUILD / 'classes.dex', 'classes.dex')
    key = ROOT / '.build-virtual-display' / ('emulator-proof-r10-api'+str(API)) / 'fixture.keystore'
    key.parent.mkdir(parents=True,exist_ok=True)
    if not key.exists():
        subprocess.run([str(JAVA / 'keytool.exe'), '-genkeypair', '-keystore', str(key), '-storepass', 'fixture-only', '-keypass', 'fixture-only', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30', '-dname', 'CN=Harness Test Fixture'], check=True, stdout=subprocess.DEVNULL)
    apk = BUILD / 'fixture.apk'
    subprocess.run([str(JAVA / 'java.exe'), '-jar', str(TOOLS / 'lib/apksigner.jar'), 'sign', '--ks', str(key), '--ks-key-alias', 'fixture', '--ks-pass', 'pass:fixture-only', '--key-pass', 'pass:fixture-only', '--out', str(apk), str(unsigned)], check=True)
    return apk

def build_frame_probe():
    folder=BUILD/'frame-probe'; folder.mkdir(exist_ok=True)
    classes=folder/'classes'; classes.mkdir(exist_ok=True)
    cp=str(SDK)+os.pathsep+str(ROOT/'.build-phone-bridge/classes')
    subprocess.run([str(JAVA/'javac.exe'),'--release','8','-classpath',cp,'-d',str(classes),str(ROOT/'phone-bridge/tests/DisplayFrameProbe.java')],check=True)
    jar=folder/'classes.jar'
    with zipfile.ZipFile(jar,'w') as z:
        for item in classes.rglob('*.class'):z.write(item,item.relative_to(classes).as_posix())
    subprocess.run([str(JAVA/'java.exe'),'-cp',str(ROOT/'third_party/embedded/r8/r8-8.2.33.jar'),'com.android.tools.r8.D8','--min-api','30','--lib',str(SDK),'--output',str(folder),str(jar)],check=True)
    payload=folder/'probe.jar'
    with zipfile.ZipFile(payload,'w') as z:z.write(folder/'classes.dex','classes.dex')
    remote='/data/local/tmp/vega-phone/frame-probe.jar'; adb('push',str(payload),remote)
    return remote

control = lease = None
test_pin_set=False
stop = threading.Event()
lease_errors = []
try:
    require(adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'only the task emulator may be modified')
    require(adb('emu','avd','name').splitlines()[0]=='HarnessVD10API'+str(API),'only the revision10 owned AVD may be modified')
    require(adb('shell', 'getprop', 'ro.build.version.sdk') == str(API), 'expected API')
    require('uid=2000' in adb('shell', 'id'), 'real shell authority')
    require(adb('shell', 'getprop', 'sys.boot_completed') == '1', 'boot completed')
    record('environment', {'serial': SERIAL, 'android_api': API, 'uid': 2000, 'fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint')})
    # Unlock only the fresh, fixed qemu test device before any Bridge display exists.
    adb('shell','input','keyevent','KEYCODE_WAKEUP')
    adb('shell','wm','dismiss-keyguard')
    adb('shell','am','start','--display','0','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
    time.sleep(.3)
    record('fixture_install', adb('install', '-r', str(build_fixture())))
    require(adb('shell','pm','clear','dev.wind.phonefixture')=='Success','reset only the owned fixture between independent test sessions')
    # Only this isolated first-party fixture receives an overlay app-op; no host/Harness grants.
    adb('shell','appops','set','dev.wind.phonefixture','SYSTEM_ALERT_WINDOW','allow')
    adb('shell','pm','revoke','dev.wind.phonefixture','android.permission.READ_CONTACTS')
    payload = ROOT / 'assets/embedded/phone/vega-phone.jar'
    digest = hashlib.sha256(payload.read_bytes()).hexdigest()
    path = '/data/local/tmp/vega-phone/bridge-' + digest + '.jar'
    adb('shell', 'mkdir', '-p', '/data/local/tmp/vega-phone')
    adb('shell', 'chmod', '700', '/data/local/tmp/vega-phone')
    adb('push', str(payload), path)
    require(adb('shell', 'sha256sum', path).split()[0] == digest, 'actual bridge bytes')
    record('bridge_payload',{'sha256':digest,'remote_bytes_verified':True})
    session, secret = uuid.uuid4().hex, uuid.uuid4().hex + uuid.uuid4().hex
    config = {'session': session, 'secret': secret, 'allow_ui': True, 'allow_screen': True, 'virtual_only': True, 'stdio_control': True, 'allowed_packages': ['dev.wind.phonefixture']}
    control = Pipe("CLASSPATH='" + path + "' exec app_process / dev.wind.phonebridge.Main")
    control.write((json.dumps(config) + '\n').encode()); control.auth('control', session, secret)
    record('bridge_status', control.rpc('status', {}, session))
    lease = Pipe("CLASSPATH='" + path + "' exec app_process / dev.wind.phonebridge.SocketRelay " + session + ' lease')
    lease.auth('lease', session, secret)
    def renew():
        while not stop.is_set():
            try: require(lease.rpc('ping', {}, session)['status'] == 'COMPLETED', 'lease response')
            except Exception as failure: lease_errors.append(str(failure)); break
            stop.wait(1)
    heartbeat = threading.Thread(target=renew, daemon=True); heartbeat.start()
    if OPTIONS.lifecycle_only or OPTIONS.retained_task_only:
        adb('shell','am','start','--display','0','-n','dev.wind.phonefixture/.MainActivity')
        time.sleep(1)
        until=time.monotonic()+10
        while True:
            main=control.rpc('observe',{'screen':'main','mode':'ui','fresh':True},session)
            if main.get('status')=='COMPLETED' and any(n['editable'] for n in main.get('nodes',[])): break
            if time.monotonic()>until: record('seed_main_observe_failure',main);raise AssertionError('main editor not ready')
            time.sleep(.2)
        editor=next(n for n in main['nodes'] if n['editable'])
        seed={'screen':'main','action':'set_text','node_id':editor['node_id'],'observation_id':main['observation_id'],'text':'borrowed draft'}
        req=str(uuid.uuid4()); require(control.rpc('prepare',seed,session,req)['status']=='PREPARED','seed main editor')
        require(control.rpc('commit',{'screen':'main'},session,req)['status']=='DISPATCHED','seed main draft')
        if OPTIONS.retained_task_only:
            adb('shell','am','start','--display','0','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
            time.sleep(1)
            # am kill only terminates cached processes; our passive overlay makes
            # the fixture important. Ask this test-only receiver to exit its own
            # process, leaving the saved recent task intact and no crash dialog.
            adb('shell','am','broadcast','-n',"'dev.wind.phonefixture/.MainActivity$ExitReceiver'")
            until=time.monotonic()+5
            while True:
                running=adb('shell','sh','-c','"pidof dev.wind.phonefixture || true"')
                if not running or time.monotonic()>until: break
                time.sleep(.2)
            require(not running,'fixture process really exited')
            tasks=adb('shell','dumpsys','activity','activities')
            require('dev.wind.phonefixture' in tasks,'recent task survives process exit')
            record('process_dead_task_retained',{'process_alive':False,'task_present':True})
    else:
        running=adb('shell','sh','-c','"pidof dev.wind.phonefixture || true"')
        require(not running,'fixture starts with no app process')
        require('dev.wind.phonefixture' not in adb('shell','dumpsys','activity','activities'),'fixture starts with no app task')
        record('genuine_cold_start',{'process_alive':False,'task_present':False})
    created = control.rpc('display_create', {}, session); record('create', created)
    require(created['status'] == 'CREATED' and created['display_id'] > 0, 'created secondary display')
    display = created['display_session_id']
    empty=control.rpc('observe',{'mode':'ui','fresh':True,'display_session_id':display},session)
    record('empty_window_observation',empty)
    require(empty['status']=='NOT_SUBMITTED' and empty['error']=='VIRTUAL_DISPLAY_WINDOW_NOT_READY','empty window failure is an observation state')
    time.sleep(3.5); require(not lease_errors,'observation failure must not destroy display at next heartbeat')
    def effect(args):
        args['display_session_id'] = display
        request = str(uuid.uuid4())
        prepared = control.rpc('prepare', args, session, request)
        if prepared['status'] != 'PREPARED': return prepared
        return control.rpc('commit', {}, session, request)
    if OPTIONS.lifecycle_only or OPTIONS.retained_task_only:
        refused=effect({'action':'launch','package':'dev.wind.phonefixture','component':'dev.wind.phonefixture/.MainActivity','task_policy':'new_only'})
        record('new_only_preserves_main_task',refused)
        require(refused['status']=='NOT_SUBMITTED' and refused['error']=='PACKAGE_PRESENT_ON_OTHER_DISPLAY','explicit new-only does not migrate')
    component='dev.wind.phonefixture/.MainActivity$RedirectActivity' if OPTIONS.redirect_only else 'dev.wind.phonefixture/.MainActivity'
    launch = effect({'action': 'launch', 'package': 'dev.wind.phonefixture', 'component': component})
    record('launch_singleTask_nonresizable', launch)
    require(launch['status'] == 'DISPATCHED' and launch['launch']['task_display_verified'], 'launch verified in requested display')
    def observe(expected=None):
        deadline = time.monotonic() + 15
        previous = None; since = time.monotonic()
        while True:
            result = control.rpc('observe', {'mode': 'ui', 'fresh': True, 'display_session_id': display}, session)
            if result['status'] == 'COMPLETED':
                key = (result['window'], result['structural_epoch'])
                if key != previous: previous, since = key, time.monotonic()
                if time.monotonic() - since >= .6 and result.get('quiet_ms', 0) >= 600 and (expected is None or expected(result)): return result
            if time.monotonic() > deadline:
                record('observe_failure', result)
                for service_name in ('window','activity','accessibility','display'):
                    (BUILD / ('failure-'+service_name+'.txt')).write_text(adb('shell','dumpsys',service_name),'utf-8')
                raise AssertionError('observe')
            time.sleep(.1)
    if OPTIONS.lifecycle_only or OPTIONS.redirect_only or OPTIONS.retained_task_only:
        require(any(m['verified'] for m in launch['launch']['task_moves']),'actual root moved to requested display')
        expected='borrowed draft' if OPTIONS.lifecycle_only or OPTIONS.retained_task_only else 'draft'
        observed=observe(lambda o:o['package']=='dev.wind.phonefixture' and any(n['text']==expected for n in o['nodes']))
        record('task_migration_ui_readback',observed)
        if OPTIONS.lifecycle_only:
            require(any(m['return_on_close'] for m in launch['launch']['task_moves']),'main task tracked for return')
            require('set to' in adb('shell','locksettings','set-pin','246810').lower(),'temporary secure PIN set on owned emulator')
            test_pin_set=True
            adb('shell','input','-d','0','keyevent','KEYCODE_SLEEP')
            until=time.monotonic()+15
            while True:
                locked=control.rpc('status',{},session)
                if locked.get('display',{}).get('host_keyguard_locked'): break
                if time.monotonic()>until: break
                time.sleep(.25)
            record('secure_lock_status',locked)
            require(locked['status']=='COMPLETED' and not locked['display']['host_interactive'] and locked['display']['host_keyguard_locked'],'real host asleep and keyguard locked')
            require(locked['display']['display_session_id']==display,'same virtual session while locked')
            time.sleep(12); require(not lease_errors,'independent heartbeat alive under secure lock')
            kept=control.rpc('status',{},session);record('secure_lock_12_seconds',kept)
            require(kept['display']['display_id']==created['display_id'] and kept['display']['close_reason'] is None,'same display survives secure lock')
            main=control.rpc('observe',{'screen':'main','mode':'ui'},session);record('locked_main_guard',main)
            require(main['status']=='NOT_SUBMITTED' and main['error'] in ('PHONE_LOCKED','PHONE_SCREEN_NOT_INTERACTIVE'),'main lock guard retained')
            # Authenticate the owned emulator's temporary PIN before removing it.
            # Clearing credentials while keyguard is showing leaves stale SystemUI
            # state on API31 and is not evidence of a virtual-window resume failure.
            adb('shell','input','-d','0','keyevent','KEYCODE_WAKEUP'); time.sleep(.5)
            adb('shell','input','-d','0','swipe','540','1700','540','400','300'); time.sleep(.5)
            adb('shell','input','-d','0','text','246810'); adb('shell','input','-d','0','keyevent','KEYCODE_ENTER')
            until=time.monotonic()+8
            while True:
                unlocked=control.rpc('status',{},session)
                if not unlocked['display']['host_keyguard_locked']: break
                if time.monotonic()>until: record('unlock_failure',unlocked);raise AssertionError('temporary test PIN authentication')
                time.sleep(.2)
            record('authenticated_unlock_status',unlocked)
            require('cleared' in adb('shell','locksettings','clear','--old','246810').lower(),'remove temporary PIN after authentication'); test_pin_set=False
            observed=observe(lambda o:o['package']=='dev.wind.phonefixture' and any(n['text']=='borrowed draft' for n in o['nodes']))
            require(observed['display_session_id']==display,'same session resumes after host unlock')
            changed=effect({'action':'set_text','node_id':next(n['node_id'] for n in observed['nodes'] if n['editable']),'observation_id':observed['observation_id'],'text':'after unlock'})
            require(changed['status']=='DISPATCHED','same-session input after unlock')
            observe(lambda o:any(n['text']=='after unlock' for n in o['nodes']))
            record('unlock_resume_same_session','PASS')
        elif OPTIONS.redirect_only:
            require(not any(m['return_on_close'] for m in launch['launch']['task_moves']),'new redirected task is owned, not borrowed')
            record('cold_redirect_recovered','PASS')
        else:
            record('dead_process_task_reuse','PASS')
        closed=control.rpc('display_close',{'display_session_id':display},session);record('close',closed)
        require(closed['status']=='CLOSED','explicit close')
        if OPTIONS.lifecycle_only or OPTIONS.retained_task_only:
            require(any(t['status']=='RETURNED' for t in closed['task_returns']),'borrowed main task returned before display release')
            wanted='after unlock' if OPTIONS.lifecycle_only else 'borrowed draft'
            until=time.monotonic()+10
            while True:
                main=control.rpc('observe',{'screen':'main','mode':'ui','fresh':True},session)
                if main.get('display_id')==0 and any(n['text']==wanted for n in main.get('nodes',[])): break
                if time.monotonic()>until: record('main_return_observation_failure',main);raise AssertionError('returned main task UI readback')
                time.sleep(.2)
            require(main['display_id']==0 and any(n['text']==wanted for n in main['nodes']),'main draft and same app task preserved')
            record('borrowed_task_return_readback','PASS')
        else:
            require('dev.wind.phonefixture' not in adb('shell','dumpsys','activity','activities'),'new redirected task destroyed without main fallback')
        report['status']='BRIDGE_API'+str(API)+'_LIFECYCLE_TASKS_PASS'
        raise SystemExit(0)
    observed = observe(lambda o:o['package']=='dev.wind.phonefixture' and any(w['type']!=1 for w in o['window_routing']['windows']))
    record('first_observation', observed)
    require(observed['package'] == 'dev.wind.phonefixture' and observed['display_id'] == created['display_id'], 'per-display UI scope')
    record('mixed_window_routing',observed['window_routing'])
    require(any(w['type']!=1 for w in observed['window_routing']['windows']),'real non-application window on secondary display')
    require(observed['window_routing']['selected_type']==1,'application tree chosen beside passive system overlay')
    editor = next(node for node in observed['nodes'] if node['editable'])
    edited = effect({'action': 'set_text', 'node_id': editor['node_id'], 'observation_id': observed['observation_id'], 'text': 'Harness virtual OK'})
    record('set_text_dispatch',edited)
    require(edited['status'] == 'DISPATCHED', 'set_text dispatch'); time.sleep(.2)
    observed = observe(); require(any(n['text'] == 'Harness virtual OK' for n in observed['nodes']), 'set_text readback')
    record('set_text_readback', {'status': 'PASS', 'display_id': observed['display_id']})
    button = next(node for node in observed['nodes'] if node['class'].endswith('.Button') and node['text'].lower()=='increment')
    clicked = effect({'action': 'click', 'node_id': button['node_id'], 'observation_id': observed['observation_id']})
    record('click_dispatch',clicked)
    require(clicked['status'] == 'DISPATCHED', 'click dispatch'); time.sleep(.2)
    observed = observe(); require(any(n['text'] == 'Count: 1' for n in observed['nodes']), 'click readback')
    record('click_readback', 'PASS')
    button = next(node for node in observed['nodes'] if node['class'].endswith('.Button') and node['text'].lower()=='increment'); left, top, right, bottom = button['bounds']
    tapped = effect({'action': 'tap', 'node_id': button['node_id'], 'observation_id': observed['observation_id'], 'x': (left + right) * .5 * 1000 / 719, 'y': (top + bottom) * .5 * 1000 / 1279})
    record('tap_dispatch',tapped)
    require(tapped['status'] == 'DISPATCHED', 'touch dispatch'); time.sleep(.2)
    observed = observe(); require(any(n['text'] == 'Count: 2' for n in observed['nodes']), 'touch readback')
    record('display_bound_touch_readback', 'PASS')
    # Public Presentation windows have no AccessibilityWindowInfo type mapping on AOSP12/15.
    # Force the test's PRIMARY launcher to own focus, never change product focus policy.
    show=next(node for node in observed['nodes'] if node['text'].lower()=='show presentation')
    shown=effect({'action':'click','node_id':show['node_id'],'observation_id':observed['observation_id']})
    record('presentation_show',shown); require(shown['status']=='DISPATCHED','presentation created')
    adb('shell','am','start','--display','0','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
    time.sleep(.4)
    observed=observe(lambda o:any(n['text']=='Unfocused presentation' for n in o['nodes'])); record('unfocused_unknown_window',observed)
    routing=observed['window_routing']; selected=next(w for w in routing['windows'] if w['id']==routing['selected_id'])
    require(selected['type']==-1 and not selected['active'] and not selected['focused'],'actual type -1, inactive, unfocused target')
    require(routing['selection']=='UNKNOWN_TYPE_ROOT_PROBE' and any(p['readable'] for p in routing['root_probes']),'actual unknown root probed without input focus')
    require(observed['display_id']==created['display_id'] and any(n['text']=='Unfocused presentation' for n in observed['nodes']),'unknown window UI read on secondary display')
    editor=next(n for n in observed['nodes'] if n['editable'])
    written=effect({'action':'set_text','node_id':editor['node_id'],'observation_id':observed['observation_id'],'text':'Unknown window OK'})
    record('unknown_window_set_text',written); require(written['status']=='DISPATCHED','unknown window text action')
    observed=observe(); require(any(n['text']=='Unknown window OK' for n in observed['nodes']),'unknown text readback')
    button=next(n for n in observed['nodes'] if n['text'].lower()=='presentation increment')
    clicked=effect({'action':'click','node_id':button['node_id'],'observation_id':observed['observation_id']})
    record('unknown_window_click',clicked); require(clicked['status']=='DISPATCHED','unknown window node click')
    observed=observe(); require(any(n['text']=='Presentation count: 1' for n in observed['nodes']),'unknown click readback')
    record('unknown_window_actions_readback','PASS')
    (BUILD/'unknown-window-system.txt').write_text(adb('shell','dumpsys','window','windows'),'utf-8')
    if API==31 and OPTIONS.layer_probe:
        probe=build_frame_probe()
        output=adb('shell',"CLASSPATH='"+path+":"+probe+"' app_process / dev.wind.phonebridge.DisplayFrameProbe "+str(created['display_id']))
        record('nonzero_display_layer_probe',{'output':output})
        if 'LAYER_CAPTURE_NONZERO_DISPLAY_OK' in output: adb('pull','/data/local/tmp/vega-phone/layer-probe.png',str(BUILD/'layer-probe.png'))
    image=Pipe("CLASSPATH='"+path+"' exec app_process / dev.wind.phonebridge.SocketRelay "+session+' image')
    try:
        image.auth('image',session,secret)
        frame=image.rpc('capture',{'display_session_id':display},session)
        record('unknown_window_capture',frame); require(frame['status']=='COMPLETED','unknown unfocused virtual frame capture')
        png=image.frame(); require(png.startswith(b'\x89PNG\r\n\x1a\n'),'unknown window actual PNG')
        (BUILD/'unknown-window.png').write_bytes(png)
        from PIL import Image
        with Image.open(BUILD/'unknown-window.png') as pixels:
            marker_count=sum(1 for red,green,blue in pixels.convert('RGB').getdata() if red>240 and green<16 and blue>240)
        report['unknown_window_visual_content']='MARKER_MATCH' if marker_count>1000 else 'MARKER_MISSING_VISUAL_NOT_ACCEPTED'
        record('unknown_window_visual_content',{'marker_pixels':marker_count,'content_verified':marker_count>1000,
            'scope':'UI actions and PNG transport are verified separately from visible window pixels'})
    finally: image.close()
    dismiss=next(n for n in observed['nodes'] if n['text'].lower()=='dismiss presentation')
    dismissed=effect({'action':'click','node_id':dismiss['node_id'],'observation_id':observed['observation_id']})
    require(dismissed['status']=='DISPATCHED','presentation dismissed')
    observed=observe(lambda o:any(n['text'].lower()=='request permission' for n in o['nodes']))
    require(observed['package']=='dev.wind.phonefixture','return to known application window')
    if OPTIONS.permission_regression:
        request=next(n for n in observed['nodes'] if n['text'].lower()=='request permission')
        requested=effect({'action':'click','node_id':request['node_id'],'observation_id':observed['observation_id']})
        record('permission_request_dispatch',requested);require(requested['status']=='DISPATCHED','permission requested')
        observed=observe(lambda o:'permissioncontroller' in o['package'])
        denied=next(n for n in observed['nodes'] if n['resource_id'].endswith('/permission_deny_button') or n['resource_id'].endswith('/permission_deny_and_dont_ask_again_button'))
        dismissed=effect({'action':'click','node_id':denied['node_id'],'observation_id':observed['observation_id']})
        record('permission_dialog_click',dismissed);require(dismissed['status']=='DISPATCHED','permission denied')
        observed=observe(lambda o:o['package']=='dev.wind.phonefixture' and any(n['text']=='Permission result returned' for n in o['nodes']))
        record('permission_dialog_return','PASS')
    else:
        record('permission_dialog_regression','NOT_RUN_IN_WEBVIEW_CONCURRENCY_SUITE')
    invalid=effect({'action':'launch','package':'dev.wind.notallowed','component':'dev.wind.notallowed/.MainActivity'})
    record('launch_preflight_rejection',invalid)
    require(invalid.get('submitted')==False and invalid.get('error')=='VIRTUAL_DISPLAY_PACKAGE_NOT_ALLOWED','invalid launch has no effect')
    time.sleep(3.5); require(not lease_errors and observe()['package']=='dev.wind.phonefixture','preflight rejection preserves usable session')
    image = Pipe("CLASSPATH='" + path + "' exec app_process / dev.wind.phonebridge.SocketRelay " + session + ' image')
    try:
        image.auth('image', session, secret)
        frame = image.rpc('capture', {'display_session_id': display}, session)
        record('capture', frame); require(frame['status'] == 'COMPLETED', 'virtual frame capture')
        png = image.frame(); require(png.startswith(b'\x89PNG\r\n\x1a\n'), 'actual PNG')
        (BUILD / 'virtual.png').write_bytes(png)
    finally: image.close()
    # Same UID2000 process explicitly observes and controls display zero while VD survives.
    main=control.rpc('observe',{'screen':'main','mode':'ui','fresh':True},session)
    record('main_observe_same_bridge',main)
    require(main['status']=='COMPLETED' and main['display_id']==0 and 'display_session_id' not in main,'explicit main read has no virtual scope')
    before=control.rpc('observe',{'display_session_id':display,'mode':'visual','fresh':True},session)
    require(before['status']=='COMPLETED','virtual visual metadata baseline')
    for i in range(8):
        adb('shell','am','start','--display','0','-a','android.settings.SETTINGS')
        adb('shell','input','-d','0','keyevent','KEYCODE_HOME')
    time.sleep(.5)
    after=control.rpc('observe',{'display_session_id':display,'mode':'visual','fresh':True},session)
    record('parallel_main_event_storm',{'before':before,'after':after})
    require(after['status']=='COMPLETED' and before['event_epoch']==after['event_epoch'] and before['structural_epoch']==after['structural_epoch'],'main changes do not stale virtual metadata')
    main=control.rpc('observe',{'screen':'main','mode':'ui','fresh':True},session)
    request=str(uuid.uuid4())
    prepared=control.rpc('prepare',{'screen':'main','observation_id':main['observation_id'],'action':'back'},session,request)
    require(prepared['status']=='PREPARED','prepare explicit main key')
    mismatch=control.rpc('commit',{},session,request)
    record('cross_screen_commit_refused',mismatch)
    require(mismatch.get('submitted')==False and mismatch['error']=='PHONE_SCREEN_SCOPE_CHANGED','prepared effect cannot change screens')
    main=control.rpc('observe',{'screen':'main','mode':'ui','fresh':True},session)
    request=str(uuid.uuid4())
    prepared=control.rpc('prepare',{'screen':'main','observation_id':main['observation_id'],'action':'back'},session,request)
    require(prepared['status']=='PREPARED','fresh main prepare')
    result=control.rpc('commit',{'screen':'main'},session,request)
    record('main_key_same_bridge',result);require(result['status']=='DISPATCHED','explicit main back dispatched')
    observed=observe(); button=next(n for n in observed['nodes'] if n['text'].lower()=='show webview')
    opened=effect({'action':'click','node_id':button['node_id'],'observation_id':observed['observation_id']})
    require(opened['status']=='DISPATCHED','open inaccessible editor fixture')
    observed=observe(lambda o:any(n['text'].startswith('Web text:') for n in o['nodes']))
    require(not any(n['editable'] for n in observed['nodes']),'WebView editor really absent from UI tree')
    record('webview_without_editor_nodes',observed)
    # HTML input at x=20..260 css px, y=20..68 + native header, density=2.
    tapped=effect({'action':'tap','observation_id':observed['observation_id'],'x':300,'y':105})
    record('webview_editor_tap',tapped);require(tapped['status']=='DISPATCHED','visual tap editor')
    time.sleep(.5);observed=observe()
    written=effect({'action':'type_text','observation_id':observed['observation_id'],'text':'Shanghai123'})
    record('webview_key_text',written);require(written['status']=='DISPATCHED','node-free key text')
    observed=observe(lambda o:any(n['text']=='Web text: Shanghai123' for n in o['nodes']))
    record('webview_ascii_dom_readback','PASS')
    written=effect({'action':'set_text','observation_id':observed['observation_id'],'text':'上海 北京 😀'})
    record('webview_unicode_paste',written);require(written['status']=='DISPATCHED','node-free Unicode paste')
    observed=observe(lambda o:any(n['text']=='Web text: 上海 北京 😀' for n in o['nodes']))
    record('webview_unicode_dom_readback','PASS')
    # Transfer focus to MAIN, then insert into the VD by explicit display ID without another VD tap.
    adb('shell','am','start','--display','0','-a','android.settings.SETTINGS')
    time.sleep(.3);observed=observe()
    written=effect({'action':'type_text','observation_id':observed['observation_id'],'text':'-OK'})
    record('webview_typing_while_main_focused',written)
    require(written['status']=='DISPATCHED','virtual keys despite main top focus')
    observed=observe(lambda o:any(n['text']=='Web text: 上海 北京 😀-OK' for n in o['nodes']))
    record('webview_background_dom_readback','PASS')
    time.sleep(10); require(not lease_errors, 'independent lease remained alive')
    record('lease_10_seconds', 'PASS')
    closed = control.rpc('display_close', {'display_session_id': display}, session); record('close', closed)
    require(closed['status'] == 'CLOSED', 'display close')
    journal=adb('shell','head','-c','4096','/data/local/tmp/vega-phone/diagnostic-'+session)
    require('state=DISPLAY_CLOSED' in journal and 'code=DISPLAY_CLOSED' in journal,'explicit release is not reported as process crash')
    record('close_cause',{'state':'DISPLAY_CLOSED','code':'DISPLAY_CLOSED'})
    require('dev.wind.phonefixture' not in adb('shell', 'dumpsys', 'activity', 'activities'), 'fixture task destroyed without migration to main')
    report['status'] = 'BRIDGE_API'+str(API)+'_UI_AND_BUFFER_PASS'
except Exception as failure:
    report['status'] = 'FAIL'; report['failure'] = repr(failure)
    raise
finally:
    if test_pin_set:
        adb('shell','locksettings','clear','--old','246810')
        adb('shell','input','-d','0','keyevent','KEYCODE_SLEEP')
        adb('shell','input','-d','0','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
        adb('shell','input','-d','0','swipe','540','1700','540','400','300')
    stop.set()
    if control: control.close()
    if lease: lease.close()
    (BUILD / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
