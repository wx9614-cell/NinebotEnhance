#!/usr/bin/env python3
"""Deterministic inputs, offline Android build, host tests and signed artifact validation."""
from pathlib import Path
import argparse, datetime, hashlib, json, os, re, shutil, struct, subprocess, sys, uuid, zipfile, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.ichinomiya.ninebotenhance'
if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def properties(path):
    return dict(line.strip().split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if line.strip() and not line.startswith('#'))

def tool(folder, name):
    return folder / (name + ('.exe' if os.name == 'nt' else ''))

def run(args, records):
    args = list(map(str, args))
    if Path(args[0]).stem in ('java','javac','keytool'): args.insert(1, '-Dfile.encoding=UTF-8' if Path(args[0]).stem == 'java' else '-J-Dfile.encoding=UTF-8')
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
    records.append({'command':args, 'exit':result.returncode, 'output':result.stdout})
    if result.returncode or 'PASS:' in result.stdout: print(result.stdout.strip())
    if result.returncode: raise RuntimeError('Command failed: ' + Path(args[0]).name)
    return result.stdout

def defined_classes(data):
    u32=lambda off:struct.unpack_from('<I',data,off)[0]
    strings=[]
    for i in range(u32(56)):
        off=u32(u32(60)+i*4)
        while data[off]&128:off+=1
        off+=1;strings.append(data[off:data.index(0,off)].decode('utf-8',errors='replace'))
    types=[strings[u32(u32(68)+i*4)] for i in range(u32(64))]
    return {types[u32(u32(100)+i*32)] for i in range(u32(96))}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'),required=not (os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')))
    parser.add_argument('--jdk',default=os.environ.get('JAVA_HOME'),required=not os.environ.get('JAVA_HOME'))
    parser.add_argument('--platform',default='android-36.1')
    parser.add_argument('--build-tools',default='37.0.0')
    parser.add_argument('--keystore',type=Path,default=ROOT/'signing/release.jks')
    parser.add_argument('--password-file',type=Path,default=ROOT/'signing/password.txt')
    parser.add_argument('--key-alias',default='ninebot-enhance')
    parser.add_argument('--allow-other-signer',action='store_true',help='Allow a developer/CI certificate instead of the pinned release certificate')
    args=parser.parse_args()
    sdk,jdk=Path(args.sdk),Path(args.jdk);bt=sdk/'build-tools'/args.build_tools
    android=sdk/'platforms'/args.platform/'android.jar'
    for required in [android,tool(jdk/'bin','javac'),tool(bt,'aapt2'),bt/'lib/d8.jar',args.keystore,args.password_file]:
        if not required.is_file():raise RuntimeError('Missing build input: '+str(required))
    version=properties(ROOT/'version.properties');name=version['versionName'];code=version['versionCode']
    assert re.fullmatch(r'[\w.\-]+',name) and code.isdecimal()
    protocol=(ROOT/'app/src/main/java/dev/ichinomiya/ninebotenhance/ipc/Protocol.java').read_text(encoding='utf-8')
    assert re.search(r'\bVERSION\s*=\s*"'+re.escape(name)+r'"',protocol), 'Protocol.VERSION differs from version.properties'
    assert re.search(r'\bVERSION_CODE\s*=\s*'+code+r'\s*;',protocol), 'Protocol.VERSION_CODE differs from version.properties'
    checksums=json.loads((ROOT/'libs/checksums.json').read_text(encoding='utf-8'))
    for filename,expected in checksums.items():
        assert hashlib.sha256((ROOT/'libs'/filename).read_bytes()).hexdigest()==expected, 'Dependency checksum mismatch: '+filename
    work=ROOT/'build'/('run-'+uuid.uuid4().hex[:10]);work.mkdir(parents=True)
    dist=ROOT/'dist';dist.mkdir(exist_ok=True);records=[]
    try:
        shutil.copyfile(android,work/'android.jar');android=work/'android.jar'
        generated,classes,dex=work/'generated',work/'classes',work/'dex'
        for folder in [generated,classes,dex]:folder.mkdir()
        runtime=[]
        for aar in sorted((ROOT/'libs').glob('shizuku-*.aar')):
            with zipfile.ZipFile(aar) as archive:
                jar=work/(aar.stem+'.jar');jar.write_bytes(archive.read('classes.jar'));runtime.append(jar)
        compile_only=[ROOT/'libs/libxposed-api-101.0.1.jar',ROOT/'libs/androidx-annotation-1.3.0.jar']
        run([tool(bt,'aapt2'),'compile','--dir',ROOT/'app/src/main/res','-o',work/'resources.zip'],records)
        ET.register_namespace('android','http://schemas.android.com/apk/res/android')
        manifest_source=ET.parse(ROOT/'app/src/main/AndroidManifest.xml');manifest_root=manifest_source.getroot()
        manifest_root.set('package',PACKAGE);manifest_root.set('{http://schemas.android.com/apk/res/android}versionName',name)
        manifest_root.set('{http://schemas.android.com/apk/res/android}versionCode',code)
        build_manifest=work/'AndroidManifest.xml';manifest_source.write(build_manifest,encoding='utf-8',xml_declaration=True)
        run([tool(bt,'aapt2'),'link','-I',android,'--manifest',build_manifest,'--java',generated,
             '--min-sdk-version','30','--target-sdk-version','36','-o',work/'base.apk',work/'resources.zip'],records)
        sources=sorted((ROOT/'app/src/main/java').rglob('*.java'))+sorted(generated.rglob('*.java'))
        response=work/'sources.rsp';response.write_text('\n'.join('"'+p.as_posix()+'"' for p in sources),encoding='utf-8')
        run([tool(jdk/'bin','javac'),'-encoding','UTF-8','--release','17','-classpath',os.pathsep.join(map(str,[android]+compile_only+runtime)),'-d',classes,'@'+str(response)],records)
        tests=work/'test-classes';tests.mkdir()
        run([tool(jdk/'bin','javac'),'-encoding','UTF-8','--release','17','-classpath',os.pathsep.join(map(str,[classes,android])),'-d',tests]+sorted((ROOT/'tests/java').glob('*.java')),records)
        # Compile the device-only Canvas checks even when no ADB device is available.
        device_tests=work/'android-test-classes';device_tests.mkdir()
        run([tool(jdk/'bin','javac'),'-encoding','UTF-8','--release','17','-classpath',os.pathsep.join(map(str,[classes,android])),'-d',device_tests]+sorted((ROOT/'tests/android').glob('*.java')),records)
        test_output=run([tool(jdk/'bin','java'),'-cp',os.pathsep.join(map(str,[tests,classes,android])),'CoreTests'],records)
        jar=work/'program.jar'
        with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
            for p in sorted(classes.rglob('*.class')):archive.write(p,p.relative_to(classes).as_posix())
        run([tool(jdk/'bin','java'),'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','30','--lib',android,
             '--classpath',compile_only[0],'--classpath',compile_only[1],'--output',dex,jar]+runtime,records)
        unsigned=work/'unsigned.apk';shutil.copyfile(work/'base.apk',unsigned)
        resources=ROOT/'app/src/main/resources'
        with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as archive:
            for p in dex.glob('*.dex'):archive.write(p,p.name)
            for p in sorted(resources.rglob('*')):
                if p.is_file() and p.relative_to(resources).as_posix()!='META-INF/NOTICE.txt':archive.write(p,p.relative_to(resources).as_posix())
            archive.write(ROOT/'THIRD_PARTY_NOTICES.md','META-INF/NOTICE.txt')
        aligned=work/'aligned.apk';run([tool(bt,'zipalign'),'-f','-P','16','4',unsigned,aligned],records)
        apk=dist/('NinebotEnhance-'+name+'.apk')
        run([tool(jdk/'bin','java'),'-jar',bt/'lib/apksigner.jar','sign','--ks',args.keystore,'--ks-key-alias',args.key_alias,
             '--ks-pass','file:'+str(args.password_file),'--out',apk,aligned],records)
        signature=run([tool(jdk/'bin','java'),'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk],records)
        certificate=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',signature).group(1)
        pin=ROOT/'release-signing-certificate.txt'
        if pin.exists() and not args.allow_other_signer:assert pin.read_text().strip()==certificate,'Release certificate mismatch'
        run([tool(bt,'zipalign'),'-c','-P','16','4',apk],records)
        badging=run([tool(bt,'aapt2'),'dump','badging',apk],records)
        assert f"package: name='{PACKAGE}'" in badging and f"versionCode='{code}'" in badging and f"versionName='{name}'" in badging
        assert "application-label:'Ninebot Enhance'" in badging and "launchable-activity: name='"+PACKAGE+".ui.ModuleActivity'" in badging
        manifest=run([tool(bt,'aapt2'),'dump','xmltree',apk,'--file','AndroidManifest.xml'],records)
        assert len(re.findall(r'^\s*E: activity\s',manifest,re.MULTILINE)) == 8
        assert '.ui.LaunchAppPickerActivity' in manifest and '.ui.TouchSettingsActivity' in manifest
        assert '.ui.NotificationSettingsActivity' in manifest and '.notification.MirrorNotificationListener' in manifest
        assert 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest
        assert '.ui.ScreenCaptureConsentActivity' in manifest and '.service.ScreenCaptureService' in manifest
        assert '.ui.LampSettingsActivity' in manifest
        assert 'android.permission.BLUETOOTH_CONNECT' in manifest and 'android.permission.BLUETOOTH_SCAN' in manifest
        assert 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION' in manifest
        assert 'rikka.shizuku.ShizukuProvider' in manifest and 'android.permission.INTERACT_ACROSS_USERS_FULL' in manifest
        with zipfile.ZipFile(apk) as archive:
            assert len(archive.namelist())==len(set(archive.namelist())), 'Duplicate APK entries'
            for license_file in ['Apache-2.0.txt','Shizuku-MIT.txt','NinebotEnhance-Apache-2.0.txt']:
                assert len(archive.read('META-INF/licenses/'+license_file))>1000, 'Missing third-party license'
            assert archive.read('META-INF/licenses/NinebotEnhance-Apache-2.0.txt') == (ROOT/'LICENSE').read_bytes(), 'Project license mismatch'
            assert archive.read('META-INF/NOTICE.txt') == (ROOT/'app/src/main/resources/META-INF/NOTICE.txt').read_bytes(), 'Gradle/offline notices differ'
            assert archive.read('META-INF/xposed/java_init.list').strip()==(PACKAGE+'.hook.MirrorModule').encode()
            assert archive.read('META-INF/xposed/scope.list').split()==[b'cn.ninebot.ninebot',b'com.autonavi.minimap',b'com.tencent.map',b'com.baidu.BaiduMap']
            assert b'staticScope=true' in archive.read('META-INF/xposed/module.prop').splitlines()
            definitions=set().union(*(defined_classes(archive.read(n)) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex',n)))
            for required in ['hook/MirrorModule','service/FrameBridgeService','service/RootBridgeProvider','display/RootDisplayMain','privilege/PrivilegeManager','privilege/PrivilegedLauncher','privilege/RootAuthorization','core/AuthorizedShell','core/StartPermission','core/PictureSource','core/DrawSettings','core/DrawLayout','notification/DrawPanel','service/DrawSession','ui/DrawSettingsDialog','ui/SetupGuideActivity','ui/PermissionRows','ui/ReadSettingsDialog','platform/Gatt','platform/BlePermissions','core/Streams','core/FramePacer','diagnostics/StreamStats','hook/StatisticsHooks','ui/StatisticsDialog','hook/EncodingHooks','diagnostics/EncodingDiagnostics','diagnostics/EncodingFormat','diagnostics/CaptureConfigReader','diagnostics/WeakIdentityMap','lamp/LampController','ui/LampSettingsActivity','core/TxLampProtocol']:
                assert 'L'+PACKAGE.replace('.','/')+'/'+required+';' in definitions,required
            for required in ['Lrikka/shizuku/Shizuku;','Lrikka/sui/Sui;','Lrikka/shizuku/ShizukuProvider;']:assert required in definitions
            for required in ['core/CaptureSize','core/ProjectionGrant','service/ProjectionSession','service/ScreenCaptureService','ui/ScreenCaptureConsentActivity','ui/RecordingPanel']:
                assert 'L'+PACKAGE.replace('.','/')+'/'+required+';' in definitions,required
            for required in ['core/LogArchive','service/LogShareProvider','service/LogExport','client/LogExporter','platform/ModuleResources','ui/AboutDialog','ui/LogDialog','ui/SettingsFooter']:
                assert 'L'+PACKAGE.replace('.','/')+'/'+required+';' in definitions,required
            for required in ['core/NotificationTimeline','notification/DashboardHud','notification/NotificationHub','notification/PhoneStatus','notification/NotificationPreferences','notification/MirrorNotificationListener','ui/NotificationSettingsActivity','ui/LaunchAppPickerActivity']:
                assert 'L'+PACKAGE.replace('.','/')+'/'+required+';' in definitions,required
            for required in ['core/MusicPlayback','notification/MusicStatus','core/BatteryTelemetry','hook/VehicleHooks','core/TireTelemetry','hook/TirePressureHooks','ui/WidgetOptionsDialog','ui/WidgetConditionDialog','ui/RangeBar','ui/WidgetSettingsDialog','core/WidgetCondition','core/CardMotion','notification/VolumeStatus','notification/DashboardOcclusion','hook/HookCatalog','core/RegisterProbe','core/RideState','core/HillHoldDetector','ui/RegisterProbeDialog']:
                assert 'L'+PACKAGE.replace('.','/')+'/'+required+';' in definitions,required
            assert not any(n.startswith(('Lio/github/libxposed/api/','Ldev/ninebot/mirror/')) for n in definitions)
            assert not any(n.endswith(('.jks','.keystore','password.txt')) for n in archive.namelist())
        # The artifact is a runtime module only. It must never contain the Ninebot host APK.
        xposed_scope = (ROOT/'app/src/main/resources/META-INF/xposed/scope.list').read_text(encoding='utf-8').split()
        assert 'cn.ninebot.ninebot' in xposed_scope, 'Ninebot host package missing from module scope'
        assert (ROOT/'app/src/main/resources/META-INF/xposed/java_init.list').read_text(encoding='utf-8').strip() == PACKAGE+'.hook.MirrorModule'
        sha=hashlib.sha256(apk.read_bytes()).hexdigest();(dist/'SHA256SUMS.txt').write_text(sha+'  '+apk.name+'\n',encoding='utf-8')
        (dist/'artifact-checks.json').write_text(json.dumps({'version':name,'version_code':int(code),'package':PACKAGE,'apk':apk.name,'apk_size':apk.stat().st_size,
            'sha256':sha,'signing_certificate_sha256':certificate,'dex_classes':len(definitions),'host_assertions':int(re.search(r'PASS: (\d+)',test_output).group(1)),
            'static_scope':['cn.ninebot.ninebot'],'xposed_api_bundled':False,'shizuku_sui_api':'13.1.5','shizuku_user_service':True,
            'android_runtime_tested':False,'m5p_tested':False,'shizuku_sui_device_tested':False,
            'system_screen_capture':True,'system_screen_capture_device_tested':False,
            'project_license':'Apache-2.0','log_file_sharing':True,'log_sharing_device_tested':False,
            'notification_hud':True,'phone_status_hud':True,'notification_hud_device_tested':False,
            'vehicle_hud':True,'vehicle_read_commands':['rTirePressureRealTimeInfo','rVoltage','rVoltage2','rVoltage3','rVrlaVoltage'],'vehicle_bay_flags':'rBool','vehicle_hud_device_tested':False,
            'statistics_semantics':'Per-session capture, selected encoder callback, selected RTP submission method; no vehicle acknowledgement or radio throughput claim'},indent=2),encoding='utf-8')
        print('APK: '+str(apk)+'\nSHA256: '+sha)
    finally:
        (dist/'build-verification.json').write_text(json.dumps({'time_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'commands':records},ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':main()
