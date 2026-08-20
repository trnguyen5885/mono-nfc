import { createHash } from 'node:crypto';
import { readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

const flutterRoot = path.resolve(import.meta.dirname, '..');
const repositoryRoot = path.resolve(flutterRoot, '..');
const androidPackageRoot = path.join(flutterRoot, 'identity_nfc_android');
const iosPackageRoot = path.join(flutterRoot, 'identity_nfc_ios');

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

const coreVersion = (await readFile(
  path.join(repositoryRoot, 'native', 'ios', 'NFCCore', 'VERSION'),
  'utf8'
)).trim();
const androidPath = 'android/libs/nfc-core.aar';
const selfContainedPath = 'ios/Frameworks/self-contained/NFCCore.xcframework';
const hostOpenSslPath = 'ios/Frameworks/host-openssl/NFCCore.xcframework';
const hostResourcesPath = 'ios/Frameworks/host-openssl/NFCCoreResources.bundle';

const androidManifest = {
  schemaVersion: 1,
  packageName: 'identity_nfc_android',
  packageVersion: await packageVersion(androidPackageRoot),
  nativeCore: {
    platform: 'android',
    version: coreVersion,
    path: androidPath,
    sha256: await digest(path.join(androidPackageRoot, androidPath)),
  },
};

const iosManifest = {
  schemaVersion: 1,
  packageName: 'identity_nfc_ios',
  packageVersion: await packageVersion(iosPackageRoot),
  nativeCore: {
    platform: 'ios',
    version: coreVersion,
    variants: {
      'self-contained': {
        linkage: 'dynamic',
        path: selfContainedPath,
        sha256: await digest(path.join(iosPackageRoot, selfContainedPath)),
      },
      'host-openssl': {
        linkage: 'static',
        path: hostOpenSslPath,
        sha256: await digest(path.join(iosPackageRoot, hostOpenSslPath)),
        resourceBundle: hostResourcesPath,
        resourceBundleSha256: await digest(path.join(iosPackageRoot, hostResourcesPath)),
      },
    },
  },
};

await Promise.all([
  writeFile(
    path.join(androidPackageRoot, 'native-bundle.json'),
    `${JSON.stringify(androidManifest, null, 2)}\n`
  ),
  writeFile(
    path.join(iosPackageRoot, 'native-bundle.json'),
    `${JSON.stringify(iosManifest, null, 2)}\n`
  ),
]);

process.stdout.write('Wrote Flutter native-bundle manifests.\n');
