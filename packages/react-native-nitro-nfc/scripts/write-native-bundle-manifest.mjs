import { createHash } from 'node:crypto';
import { readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const packageRoot = path.resolve(import.meta.dirname, '..');
const packageJson = JSON.parse(
  await readFile(path.join(packageRoot, 'package.json'), 'utf8')
);

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

function asset(relativePath) {
  return path.join(packageRoot, relativePath);
}

const paths = {
  android: 'android/libs/nfc-core.aar',
  iosSelfContained: 'ios/Frameworks/self-contained/NFCCore.xcframework',
  iosHostOpenSsl: 'ios/Frameworks/host-openssl/NFCCore.xcframework',
  iosHostResources: 'ios/Frameworks/host-openssl/NFCCoreResources.bundle',
};

const manifest = {
  schemaVersion: 1,
  adapterVersion: packageJson.version,
  android: {
    version: packageJson.nativeCoreVersions.android,
    path: paths.android,
    sha256: await digest(asset(paths.android)),
  },
  ios: {
    version: packageJson.nativeCoreVersions.ios,
    variants: {
      'self-contained': {
        linkage: 'dynamic',
        path: paths.iosSelfContained,
        sha256: await digest(asset(paths.iosSelfContained)),
      },
      'host-openssl': {
        linkage: 'static',
        path: paths.iosHostOpenSsl,
        sha256: await digest(asset(paths.iosHostOpenSsl)),
        resourceBundle: paths.iosHostResources,
        resourceBundleSha256: await digest(asset(paths.iosHostResources)),
      },
    },
  },
};

await writeFile(
  path.join(packageRoot, 'native-bundle.json'),
  `${JSON.stringify(manifest, null, 2)}\n`
);

process.stdout.write('Wrote native-bundle.json\n');
