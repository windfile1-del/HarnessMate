const fs = require("node:fs"), path = require("node:path"), cp = require("node:child_process"), crypto = require("node:crypto");
const root = path.resolve(__dirname, "../..");
process.chdir(root);
const scratch = path.join(root, ".build-p7-tools/host-tests-packages");
fs.mkdirSync(scratch, { recursive: true });
const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
const java = process.env.VEPRO_HOST_JAVA || (fs.existsSync("D:/VSCode/bin/java.exe") ? "D:/VSCode/bin/java.exe" : "java");
const javac = process.env.VEPRO_HOST_JAVAC || (fs.existsSync("D:/VSCode/bin/javac.exe") ? "D:/VSCode/bin/javac.exe" : "javac");
const production = path.join(root, ".build-p7-tools/production-check");
const summaryFile = path.join(production, "summary.json");
if (!fs.existsSync(summaryFile)) throw new Error("production-check/summary.json not found; run verification first");
const summary = JSON.parse(fs.readFileSync(summaryFile, "utf8"));
for (const [file, sha] of Object.entries(summary.sourceHashes)) {
    const full = path.join(root, file);
    if (!fs.existsSync(full)) throw new Error("MISSING_PRODUCTION_SOURCE: " + file);
    const actualSha = crypto.createHash("sha256").update(fs.readFileSync(full)).digest("hex");
    if (actualSha !== sha) throw new Error("STALE_PRODUCTION_SOURCE: " + file + " expected=" + sha + " actual=" + actualSha);
}
console.log("Production source hashes verified: " + Object.keys(summary.sourceHashes).length + " files");
const upstream = walk("third_party/embedded/kotlin/upstream").filter(f => f.endsWith(".jar") && !f.endsWith("-sources.jar"));
const libraries = [
    ...upstream,
    ...walk("third_party/embedded").filter(f => f.endsWith(".jar") && !f.includes("kotlin") && !f.includes("desugar") && !f.endsWith("-sources.jar")),
    ...fs.readFileSync("third_party/webkit/runtime-jars.txt", "utf8").trim().split(/\r?\n/).filter(Boolean)
].map(f => path.resolve(f));
const jsonDir = path.resolve(".build-p7-tools/host-tests/json");
const compileClasspath = [path.join(production, "classes"), path.join(production, "java"), jsonDir, path.resolve("assets/embedded/android-35/android.jar"), ...libraries].join(path.delimiter);
function run(name, exe, args) {
    const log = path.join(scratch, name + ".log"), fd = fs.openSync(log, "w");
    const r = cp.spawnSync(exe, args, { stdio: ["ignore", fd, fd] });
    fs.closeSync(fd);
    const output = fs.readFileSync(log, "utf8");
    if (r.status !== 0) { process.stderr.write(output); throw new Error(name + " failed: " + r.status); }
    console.log(name + " PASS");
    return output;
}
const testsOut = path.join(scratch, "classes");
fs.mkdirSync(testsOut, { recursive: true });
const argsFile = path.join(scratch, "tests.rsp");
fs.writeFileSync(argsFile, [path.join(__dirname, "ProjectPackageTests.kt")].map(f => '"' + f.replaceAll("\\", "/") + '"').join("\n"));
run("tests-compile", java, [
    "-Xmx1g", "-Dfile.encoding=UTF-8", "-cp", upstream.map(f => path.resolve(f)).join(path.delimiter),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-jvm-target", "17",
    "-classpath", compileClasspath,
    "-d", testsOut, "@" + argsFile
]);
const runClasspath = [testsOut, jsonDir, path.join(production, "classes"), path.join(production, "java"), path.resolve("assets/embedded/android-35/android.jar"), ...libraries].join(path.delimiter);
const output = run("ProjectPackageTests", java, [
    "-Dfile.encoding=UTF-8", "-cp", runClasspath, "dev.wind.agent.packages.ProjectPackageTests"
]);
fs.writeFileSync(path.join(scratch, "summary.json"), JSON.stringify({
    status: "SOURCE_AND_HOST_VERIFIED",
    suite: "dev.wind.agent.packages.ProjectPackageTests",
    output: output.trim(),
    device: "DEVICE_NOT_RUN",
    apk: "NOT_PACKAGED"
}, null, 2));
console.log(output.trim());
