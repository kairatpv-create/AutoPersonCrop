#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
  echo 'iOS build unavailable: macOS and Xcode are required. No IPA was built.' >&2
  exit 2
fi
command -v xcodebuild >/dev/null
command -v xcrun >/dev/null
xcodebuild -version
xcode_major=$(xcodebuild -version | awk '/^Xcode /{split($2,v,"."); print v[1]}')
sdk_version=$(xcrun --sdk iphoneos --show-sdk-version)
sdk_major=${sdk_version%%.*}
if [[ "$xcode_major" -lt 26 || "$sdk_major" -lt 26 ]]; then
  echo 'App Store upload baseline requires Xcode 26+ and iOS SDK 26+ as checked on 2026-10-21.' >&2
  exit 3
fi
xcrun simctl list devices available
project='mobile/ios/OrganizerPro.xcodeproj'
if [[ ! -d "$project" ]]; then
  echo "Missing actual iOS project: $project. The audit preparation is not an iOS application." >&2
  exit 4
fi
xcodebuild -list -project "$project"
echo 'Environment preflight completed. Compilation, signing and device testing are still separate checks.'
