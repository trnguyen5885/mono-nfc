const path = require('path');
const pkg = require('../../packages/react-native-nitro-nfc/package.json');
const packageRoot = path.resolve(__dirname, '../../packages/react-native-nitro-nfc');

module.exports = {
  project: {
    ios: {
      automaticPodsInstallation: true,
    },
  },
  dependencies: {
    [pkg.name]: {
      root: packageRoot,
      platforms: {
        // Codegen script incorrectly fails without this
        // So we explicitly specify the platforms with empty object
        ios: {},
        android: {},
      },
    },
  },
};
