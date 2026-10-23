#!/usr/bin/env bash
# Local archive/export only; never registers Apple resources or uploads a build.
set -euo pipefail
umask 077
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
bash tools/ios/preflight.sh
: "${IOS_TEAM_ID:?Set the existing Apple Team ID}"
: "${IOS_PROFILE_NAME:?Set the name of the installed App Store provisioning profile}"
if [[ ! "$IOS_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]]; then echo 'Invalid Team ID' >&2; exit 2; fi
ios_version=${IOS_MARKETING_VERSION:-1.0.0}
ios_build=${IOS_BUILD_NUMBER:-1}
if [[ ! "$ios_version" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ || ! "$ios_build" =~ ^[0-9]+$ ]]; then echo 'Invalid version/build' >&2; exit 2; fi
ios_output=${IOS_OUTPUT_DIR:-"$repo_root/build/ios-signed"}
mkdir -p "$ios_output"
export IOS_EXPORT_OPTIONS="$ios_output/ExportOptions.plist"
python3 - <<'PY'
import os,plistlib
options={'method':'app-store-connect','destination':'export','signingStyle':'manual','teamID':os.environ['IOS_TEAM_ID'],
 'provisioningProfiles':{'com.organizerpro.mobile':os.environ['IOS_PROFILE_NAME']},
 'manageAppVersionAndBuildNumber':False,'stripSwiftSymbols':True}
with open(os.environ['IOS_EXPORT_OPTIONS'],'wb') as file:plistlib.dump(options,file)
PY
xcodebuild -project mobile/ios/OrganizerPro.xcodeproj -scheme OrganizerPro -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ios_output/OrganizerPro.xcarchive" archive \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY='Apple Distribution' \
  DEVELOPMENT_TEAM="$IOS_TEAM_ID" PROVISIONING_PROFILE_SPECIFIER="$IOS_PROFILE_NAME" \
  MARKETING_VERSION="$ios_version" CURRENT_PROJECT_VERSION="$ios_build"
codesign --verify --deep --strict "$ios_output/OrganizerPro.xcarchive/Products/Applications/OrganizerPro.app"
xcodebuild -exportArchive -archivePath "$ios_output/OrganizerPro.xcarchive" \
  -exportPath "$ios_output/export" -exportOptionsPlist "$IOS_EXPORT_OPTIONS"
echo "Signed archive and IPA exported to $ios_output. Nothing has been uploaded."
