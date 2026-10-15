const fs = require("node:fs"), path = require("node:path"), cp = require("node:child_process");
const root = path.resolve(__dirname, "../.."); process.chdir(root);
const scratch = path.join(root, ".build-p7-vpn-downloads/host-tests");
fs.mkdirSync(scratch, { recursive: true });
const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const java = "D:/VSCode/bin/java.exe";
const deltaClasses = path.join(root, ".build-chat-render/delta/classes");
const vpnProduction = path.join(root, ".build-p7-vpn-downloads/production/classes");
const baseProduction = path.join(root, ".build-p7-tools/production-check/classes");
const baseJava = path.join(root, ".build-p7-tools/production-check/java");
const androidJar = path.resolve("assets/embedded/android-35/android.jar");
const jsonClasses = path.join(root, ".build-p7-tools/host-tests/json");
const upstream = walk("third_party/embedded/kotlin/upstream").filter(f => f.endsWith(".jar") && !f.endsWith("-sources.jar"));
const libraries = [...upstream, ...walk("third_party/embedded").filter(f => f.endsWith(".jar") && !f.includes("kotlin") && !f.includes("desugar") && !f.endsWith("-sources.jar")),
    ...fs.readFileSync("third_party/webkit/runtime-jars.txt", "utf8").trim().split(/\r?\n/)].map(f => path.resolve(f));
const compileClasspath = [deltaClasses, vpnProduction, baseProduction, baseJava, androidJar, jsonClasses, ...libraries].join(path.delimiter);
const friendPaths = [deltaClasses, vpnProduction, baseProduction].join(",");
function run(name, exe, args) {
    const log = path.join(scratch, name + ".log"), fd = fs.openSync(log, "w");
    const r = cp.spawnSync(exe, args, { stdio: ["ignore", fd, fd] }); fs.closeSync(fd);
    const output = fs.readFileSync(log, "utf8");
    return { status: r.status, output };
}
const testClassesDir = path.join(scratch, "classes");
fs.mkdirSync(testClassesDir, { recursive: true });
const rspFile = path.join(scratch, "tests.rsp");
const testSources = [
    path.join(root, "tests/dev/wind/agent/SecureArtifactDownloaderTests.kt"),
    path.join(root, "tests/dev/wind/agent/QuarantineDownloadTests.kt"),
    path.join(root, "tests/p7-vpn-downloads/VpnCompatibleDownloadsTests.kt")
];
fs.writeFileSync(rspFile, testSources.map(f => "\"" + f.replaceAll("\\", "/") + "\"").join("\n"));
console.log("Compiling tests with delta classes first...");
const compRes = run("tests-compile", java, ["-Xmx1g", "-Dfile.encoding=UTF-8", "-cp", upstream.map(f => path.resolve(f)).join(path.delimiter),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
    "-classpath", compileClasspath, "-Xfriend-paths=" + friendPaths, "-d", testClassesDir, "@" + rspFile]);
if (compRes.status !== 0) {
    console.error("tests-compile FAILED:\n" + compRes.output);
    process.exit(1);
}
console.log("tests-compile PASS");
const runClasspath = [testClassesDir, jsonClasses, deltaClasses, vpnProduction, baseProduction, baseJava, androidJar, ...libraries].join(path.delimiter);
const suites = [
    { name: "SecureArtifactDownloaderTests", mainClass: "dev.wind.agent.SecureArtifactDownloaderTests" },
    { name: "QuarantineDownloadTests", mainClass: "dev.wind.agent.QuarantineDownloadTests" },
    { name: "VpnCompatibleDownloadsTests", mainClass: "dev.wind.agent.artifact.VpnCompatibleDownloadsTests" }
];
const results = {};
let allPassed = true;
for (const s of suites) {
    const tempDir = path.join(scratch, "temp-" + s.name);
    fs.mkdirSync(tempDir, { recursive: true });
    const r = run(s.name, java, ["-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + tempDir, "-cp", runClasspath, s.mainClass, tempDir]);
    if (r.status === 0) {
        console.log(s.name + " PASS: " + r.output.trim());
        results[s.name] = { status: "PASS", output: r.output.trim() };
    } else {
        console.log(s.name + " FAIL (exit " + r.status + "):\n" + r.output.trim());
        results[s.name] = { status: "FAIL", output: r.output.trim() };
        allPassed = false;
    }
}
const summary = { status: allPassed ? "SOURCE_AND_HOST_VERIFIED" : "HOST_TESTS_FAILED", suites: results, device: "DEVICE_NOT_RUN", apk: "NOT_PACKAGED" };
fs.writeFileSync(path.join(scratch, "summary.json"), JSON.stringify(summary, null, 2));
console.log("RESULT SUMMARY:", summary.status);
