const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
process.chdir(path.resolve(__dirname,'..'));
const read=f=>fs.readFileSync(f,'utf8');
const manifest=read('AndroidManifest.xml');
for(const name of ['WebProviderActivity','WebProviderBrowserActivity','WebAttachmentProvider']){
 const component=manifest.split(/\r?\n/).find(line=>line.includes('.webprovider.'+name));
 assert.ok(component,'Missing '+name);
 if(name!=='WebAttachmentProvider')assert.ok(component.includes('exported="false"'));
}
assert.match(manifest,/android:allowBackup="false"/);
assert.match(manifest,/WebScreenTextService[\s\S]*?BIND_ACCESSIBILITY_SERVICE/);
const webDir='src/dev/wind/agent/webprovider';const sources=fs.readdirSync(webDir).filter(f=>f.endsWith('.kt')).map(f=>read(path.join(webDir,f))).join('\n');
assert.doesNotMatch(sources,/getCookie\s*\(|addJavascriptInterface\s*\(|parseToolCall\s*\(|dispatchGesture\s*\(|performAction\s*\(/);
assert.match(sources,/MULTI_PROFILE/);assert.match(sources,/setProfile\(view, binding.profileName\)/);
assert.match(sources,/SITE_STORAGE_COMPATIBILITY/);
assert.doesNotMatch(read(webDir+'/BrowserSessionManager.kt'),/else "MULTI_PROFILE_UNSUPPORTED"/);
assert.match(sources,/view\.measure\(View\.MeasureSpec/);
assert.match(read(webDir+'/WebModelTransport.kt'),/WebProviderBrowserActivity\.open\(app/);
assert.match(read('src/dev/wind/agent/MainActivity.kt'),/ComposerModelMenu\.show/);
assert.match(sources,/PREVIOUS_WEB_SEND_UNCONFIRMED_OPEN_ORIGINAL_PAGE/);
assert.match(sources,/node.isPassword/);
assert.match(read('src/dev/wind/agent/AgentEngine.kt'),/WebModelRunner.run/);
assert.match(read('src/dev/wind/agent/AgentService.kt'),/if \(chat.webBinding == null\)/);
for(const file of ['mkapk.ps1','mkapk.sh']){const source=read(file);assert.match(source,/third_party\/webkit\/runtime-jars.txt/);assert.match(source,/third_party\/webkit\/SHA256SUMS/);assert.match(source,/extra-packages androidx.core:androidx.webkit/);}
assert.match(read('build.gradle.kts'),/verifyWebProviders/);
const adapter=read('assets/webproviders/adapter.js');assert.doesNotMatch(adapter,/document\.cookie|localStorage|XMLHttpRequest|\bfetch\s*\(/);
new Function(adapter.replace('__HARNESS_KEY__','"check"').replace('__HARNESS_CONFIG__','{}'));
const html=read('assets/webproviders/fixture.html');new Function(html.match(/<script>([\s\S]*?)<\/script>/)[1]);
if(process.platform==='win32'){
 const result=cp.spawnSync('powershell',['-NoProfile','-Command',"$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile((Join-Path (Get-Location) 'mkapk.ps1'),[ref]$tokens,[ref]$errors) | Out-Null; if($errors.Count){$errors | ForEach-Object {Write-Output $_.Message}; exit 1}; [xml](Get-Content -Raw -Encoding UTF8 AndroidManifest.xml) | Out-Null"],{encoding:'utf8'});assert.equal(result.status,0,result.stderr+result.stdout);
 const bash='C:/Program Files/Git/bin/bash.exe';if(fs.existsSync(bash)){const result=cp.spawnSync(bash,['-n','mkapk.sh'],{encoding:'utf8'});assert.equal(result.status,0,result.stderr);}
}
console.log('Web Provider source integration, scripts, manifest and JavaScript syntax PASS. No packaging.');
