# Android integration documentation

Android documentation is split by audience:

- [Local Library integration](LOCAL_INTEGRATION.md): develop, publish, package and
  smoke-test `nfc-core` from this monorepo.
- [Host app integration](HOST_APP_INTEGRATION.md): integrate the released library
  into an Android application using the distributed local Maven repository.
- [Dependency and R8/ProGuard compatibility](DEPENDENCY_AND_PROGUARD.md):
  resolve the library alongside an existing host dependency graph and minified
  release build.

The legacy path is retained only so existing links keep working. Use the guide
that matches your role for all new integrations.
