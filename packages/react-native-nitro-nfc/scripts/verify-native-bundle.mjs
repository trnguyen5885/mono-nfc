import { createHash } from 'node:crypto';
import { execFile as execFileCallback } from 'node:child_process';
import { access, readFile, readdir, stat } from 'node:fs/promises';
import { promisify } from 'node:util';
import path from 'node:path';
import process from 'node:process';

const execFile = promisify(execFileCallback);

const packageRoot = path.resolve(import.meta.dirname, '..');
const packageJson = JSON.parse(
  await readFile(path.join(packageRoot, 'package.json'), 'utf8')
);
const manifestPath = path.join(packageRoot, 'native-bundle.json');

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

async function assertDigest(relativePath, expected, label) {
  const target = path.join(packageRoot, relativePath);
  await access(target);
  const actual = await digest(target);
  if (actual !== expected) {
    throw new Error(`${label} SHA-256 does not match native-bundle.json`);
  }
}

async function assertIosFramework(variant, expectedLinkage) {
  const framework = path.join(
    packageRoot,
    'ios',
    'Frameworks',
    variant,
    'NFCCore.xcframework'
  );
  const deviceBinary = path.join(
    framework,
    'ios-arm64',
    'NFCCore.framework',
    'NFCCore'
  );
  const simulatorBinary = path.join(
    framework,
    'ios-arm64_x86_64-simulator',
    'NFCCore.framework',
    'NFCCore'
  );

  await Promise.all([access(deviceBinary), access(simulatorBinary)]);

  // Mach-O inspection requires the Xcode command-line tools. Release CI is
  // macOS; on another platform the structural and checksum checks still make
  // the package safe to consume, while native release validation remains CI's
  // responsibility.
  if (process.platform !== 'darwin') return;

  const [deviceInfo, simulatorInfo] = await Promise.all([
    execFile('file', [deviceBinary]),
    execFile('file', [simulatorBinary]),
  ]);
  const [deviceSlices, simulatorSlices] = await Promise.all([
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

  const inspection = `${deviceInfo.stdout}\n${simulatorInfo.stdout}`;
  const expectedDescription = expectedLinkage === 'dynamic'
    ? 'dynamically linked shared library'
    : 'current ar archive';
  if (!inspection.includes(expectedDescription)) {
    throw new Error(`${variant} NFCCore linkage is not ${expectedLinkage}`);
  }
}

try {
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  if (manifest.schemaVersion !== 1) {
    throw new Error(`Unsupported native bundle schema: ${manifest.schemaVersion}`);
  }
  if (manifest.adapterVersion !== packageJson.version) {
    throw new Error('native-bundle.json adapterVersion does not match package.json');
  }
  if (manifest.android?.version !== packageJson.nativeCoreVersions?.android) {
    throw new Error('Android core version does not match package.json');
  }
  if (manifest.ios?.version !== packageJson.nativeCoreVersions?.ios) {
    throw new Error('iOS core version does not match package.json');
  }

  await assertDigest(manifest.android.path, manifest.android.sha256, 'Android AAR');
  await assertDigest(
    manifest.ios.variants['self-contained'].path,
    manifest.ios.variants['self-contained'].sha256,
    'Self-contained NFCCore'
  );
  await assertDigest(
    manifest.ios.variants['host-openssl'].path,
    manifest.ios.variants['host-openssl'].sha256,
    'Host-OpenSSL NFCCore'
  );
  await assertDigest(
    manifest.ios.variants['host-openssl'].resourceBundle,
    manifest.ios.variants['host-openssl'].resourceBundleSha256,
    'Host-OpenSSL resources'
  );

  if (manifest.ios.variants['self-contained'].linkage !== 'dynamic') {
    throw new Error('Self-contained NFCCore must declare dynamic linkage');
  }
  if (manifest.ios.variants['host-openssl'].linkage !== 'static') {
    throw new Error('Host-OpenSSL NFCCore must declare static linkage');
  }
  await assertIosFramework('self-contained', 'dynamic');
  await assertIosFramework('host-openssl', 'static');

  process.stdout.write('Native bundle manifest, slices, linkage, and checksums are valid.\n');
} catch (error) {
  process.stderr.write(`Native bundle validation failed: ${error.message}\n`);
  process.exitCode = 1;
}
