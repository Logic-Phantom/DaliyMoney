/**
 * DailyRoll 개인정보 보호 config plugin (prebuild·EAS Build 공통 적용)
 *
 * 1) 릴리스 빌드에서 INTERNET 권한을 제거한다. 앱의 기본 동작은 네트워크 없이 가능해야 하며,
 *    결제 정보를 외부로 보낼 수 있는 경로 자체를 없앤다. (디버그 빌드는 Metro 개발 서버 연결에 필요해 유지)
 * 2) Android 12+ 클라우드 백업·기기 간 이전에서 앱 데이터를 모두 제외한다(dataExtractionRules).
 *    구형 기기는 app.json 의 android.allowBackup=false 로 막는다.
 */
const fs = require('fs');
const path = require('path');
const { withAndroidManifest, withDangerousMod } = require('expo/config-plugins');

const RELEASE_MANIFEST = `<?xml version="1.0" encoding="utf-8"?>
<!-- withDailyRollPrivacy 가 생성: 릴리스 빌드에는 네트워크 권한이 없다 -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
</manifest>
`;

const DATA_EXTRACTION_RULES = `<?xml version="1.0" encoding="utf-8"?>
<!-- withDailyRollPrivacy 가 생성: 거래 DB·설정을 클라우드 백업과 기기 이전에서 제외 -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" />
        <exclude domain="file" />
        <exclude domain="database" />
        <exclude domain="sharedpref" />
        <exclude domain="external" />
    </device-transfer>
</data-extraction-rules>
`;

function withFiles(config) {
  return withDangerousMod(config, [
    'android',
    async (cfg) => {
      const appDir = path.join(cfg.modRequest.platformProjectRoot, 'app', 'src');
      const releaseDir = path.join(appDir, 'release');
      fs.mkdirSync(releaseDir, { recursive: true });
      fs.writeFileSync(path.join(releaseDir, 'AndroidManifest.xml'), RELEASE_MANIFEST);
      const xmlDir = path.join(appDir, 'main', 'res', 'xml');
      fs.mkdirSync(xmlDir, { recursive: true });
      fs.writeFileSync(path.join(xmlDir, 'dailyroll_data_extraction_rules.xml'), DATA_EXTRACTION_RULES);
      return cfg;
    },
  ]);
}

function withManifestAttrs(config) {
  return withAndroidManifest(config, (cfg) => {
    const app = cfg.modResults.manifest.application?.[0];
    if (app) {
      app.$['android:allowBackup'] = 'false';
      app.$['android:dataExtractionRules'] = '@xml/dailyroll_data_extraction_rules';
    }
    return cfg;
  });
}

module.exports = function withDailyRollPrivacy(config) {
  return withManifestAttrs(withFiles(config));
};
