import { createHash } from 'node:crypto';
import { execFile as execFileCallback } from 'node:child_process';
import { access, readFile, readdir, stat } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import process from 'node:process';

const execFile = promisify(execFileCallback);
const flutterRoot = path.resolve(import.meta.dirname, '..');
const repositoryRoot = path.resolve(flutterRoot, '..');

async function digest(target) {
  const info = await stat(target);
  const hash = createHash('sha256');

  if (info.isFile()) {
    hash.update(await readFile(target));
    return hash.digest('hex');
  }

  async function appendDirectory(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
      const child = path.join(directory, entry.name);
      const relative = path.relative(target, child).split(path.sep).join('/');
      if (entry.isDirectory()) {
        hash.update(`directory:${relative}\n`);
        await appendDirectory(child);
      } else if (entry.isFile()) {
        hash.update(`file:${relative}\n`);
        hash.update(await readFile(child));
      } else {
        throw new Error(`Unsupported bundle entry: ${child}`);
      }
    }
  }

  await appendDirectory(target);
  return hash.digest('hex');
}

async function packageVersion(packageRoot) {
  const pubspec = await readFile(path.join(packageRoot, 'pubspec.yaml'), 'utf8');
  const match = pubspec.match(/^version:\s*([^\s#]+)/m);
  if (!match) throw new Error(`No version in ${packageRoot}/pubspec.yaml`);
  return match[1];
}

async function assertDigest(packageRoot, relativePath, expected, label) {
  const target = path.join(packageRoot, relativePath);
  await access(target);
  if ((await digest(target)) !== expected) {
    throw new Error(`${label} SHA-256 does not match native-bundle.json`);
  }
}

async function assertIosFramework(iosPackageRoot, variant, expectedLinkage) {
  const framework = path.join(
    iosPackageRoot,
    'ios',
    'Frameworks',
    variant,
    'NFCCore.xcframework'
  );
  const deviceBinary = path.join(framework, 'ios-arm64', 'NFCCore.framework', 'NFCCore');
  const simulatorBinary = path.join(
    framework,
    'ios-arm64_x86_64-simulator',
    'NFCCore.framework',
    'NFCCore'
  );
  await Promise.all([access(deviceBinary), access(simulatorBinary)]);

  if (process.platform !== 'darwin') return;

  const [deviceInfo, simulatorInfo, deviceSlices, simulatorSlices] = await Promise.all([
    execFile('file', [deviceBinary]),
    execFile('file', [simulatorBinary]),
    execFile('xcrun', ['lipo', '-archs', deviceBinary]),
    execFile('xcrun', ['lipo', '-archs', simulatorBinary]),
  ]);
  if (!deviceSlices.stdout.trim().split(/\s+/).includes('arm64')) {
    throw new Error(`${variant} NFCCore is missing the arm64 device slice`);
  }
  const simulatorArchitectures = simulatorSlices.stdout.trim().split(/\s+/);
  for (const architecture of ['arm64', 'x86_64']) {
    if (!simulatorArchitectures.includes(architecture)) {
      throw new Error(`${variant} NFCCore is missing the ${architecture} simulator slice`);
    }
  }
  const expectedDescription = expectedLinkage === 'dynamic'
    ? 'dynamically linked shared library'
    : 'current ar archive';
  if (!`${deviceInfo.stdout}\n${simulatorInfo.stdout}`.includes(expectedDescription)) {
    throw new Error(`${variant} NFCCore linkage is not ${expectedLinkage}`);
  }
}

try {
  const androidPackageRoot = path.join(flutterRoot, 'identity_nfc_android');
  const iosPackageRoot = path.join(flutterRoot, 'identity_nfc_ios');
  const [androidManifest, iosManifest, coreVersion] = await Promise.all([
    readFile(path.join(androidPackageRoot, 'native-bundle.json'), 'utf8').then(JSON.parse),
    readFile(path.join(iosPackageRoot, 'native-bundle.json'), 'utf8').then(JSON.parse),
    readFile(path.join(repositoryRoot, 'native', 'ios', 'NFCCore', 'VERSION'), 'utf8').then(value => value.trim()),
  ]);

  if (androidManifest.schemaVersion !== 1 || iosManifest.schemaVersion !== 1) {
    throw new Error('Unsupported Flutter native bundle schema');
  }
  if (androidManifest.packageName !== 'identity_nfc_android' || iosManifest.packageName !== 'identity_nfc_ios') {
    throw new Error('Flutter native bundle manifest has the wrong package name');
  }
  if (androidManifest.packageVersion !== await packageVersion(androidPackageRoot)) {
    throw new Error('Android native bundle packageVersion does not match pubspec.yaml');
  }
  if (iosManifest.packageVersion !== await packageVersion(iosPackageRoot)) {
    throw new Error('iOS native bundle packageVersion does not match pubspec.yaml');
  }
  if (androidManifest.nativeCore?.version !== coreVersion || iosManifest.nativeCore?.version !== coreVersion) {
    throw new Error('Flutter native bundle core version does not match NFCCore/VERSION');
  }
  if (androidManifest.nativeCore?.platform !== 'android' || iosManifest.nativeCore?.platform !== 'ios') {
    throw new Error('Flutter native bundle has the wrong platform declaration');
  }

  await assertDigest(
    androidPackageRoot,
    androidManifest.nativeCore.path,
    androidManifest.nativeCore.sha256,
    'Android AAR'
  );
  const selfContained = iosManifest.nativeCore.variants?.['self-contained'];
  const hostOpenSsl = iosManifest.nativeCore.variants?.['host-openssl'];
  if (selfContained?.linkage !== 'dynamic' || hostOpenSsl?.linkage !== 'static') {
    throw new Error('Flutter iOS native bundle linkage declarations are invalid');
  }
  await assertDigest(iosPackageRoot, selfContained.path, selfContained.sha256, 'Self-contained NFCCore');
  await assertDigest(iosPackageRoot, hostOpenSsl.path, hostOpenSsl.sha256, 'Host-OpenSSL NFCCore');
  await assertDigest(
    iosPackageRoot,
    hostOpenSsl.resourceBundle,
    hostOpenSsl.resourceBundleSha256,
    'Host-OpenSSL resources'
  );
  await assertIosFramework(iosPackageRoot, 'self-contained', 'dynamic');
  await assertIosFramework(iosPackageRoot, 'host-openssl', 'static');

  process.stdout.write('Flutter native bundle manifests, slices, linkage, and checksums are valid.\n');
} catch (error) {
  process.stderr.write(`Flutter native bundle validation failed: ${error.message}\n`);
  process.exitCode = 1;
}
