#!/usr/bin/env python3
"""Reproducible identity, permission descriptions and privacy manifest for the existing Apple app."""
from pathlib import Path
import plistlib
root=Path(__file__).resolve().parents[2]/'mobile/ios/OrganizerPro'
info={
 'CFBundleDevelopmentRegion':'ru','CFBundleLocalizations':['ru','en'],'CFBundleDisplayName':'Органайзер Про',
 'CFBundleExecutable':'$(EXECUTABLE_NAME)','CFBundleIdentifier':'$(PRODUCT_BUNDLE_IDENTIFIER)','CFBundleInfoDictionaryVersion':'6.0',
 'CFBundleName':'OrganizerPro','CFBundlePackageType':'APPL','CFBundleShortVersionString':'$(MARKETING_VERSION)','CFBundleVersion':'$(CURRENT_PROJECT_VERSION)',
 'LSRequiresIPhoneOS':True,'LSSupportsOpeningDocumentsInPlace':True,'UIFileSharingEnabled':False,'ITSAppUsesNonExemptEncryption':False,
 'UIApplicationSceneManifest':{'UIApplicationSupportsMultipleScenes':False},
 'UILaunchScreen':{'UIColorName':'LaunchBackground','UIImageName':'BrandIcon'},
 'UISupportedInterfaceOrientations':['UIInterfaceOrientationPortrait','UIInterfaceOrientationLandscapeLeft','UIInterfaceOrientationLandscapeRight'],
 'BGTaskSchedulerPermittedIdentifiers':['com.organizerpro.mobile.refresh'],'UIBackgroundModes':['fetch'],
 'CFBundleDocumentTypes':[{'CFBundleTypeName':'Organizer Pro backup','CFBundleTypeRole':'Viewer','LSHandlerRank':'Alternate','LSItemContentTypes':['public.json','com.pkware.zip-archive','public.zip-archive'],'CFBundleTypeExtensions':['json','zip']}],
}
ru={
 'CFBundleDisplayName':'Органайзер Про',
 'NSCameraUsageDescription':'Снимайте фото, чтобы прикрепить их к выбранной заметке или проекту.',
 'NSMicrophoneUsageDescription':'Записывайте голосовые вложения и диктуйте текст заметок по вашему выбору.',
 'NSSpeechRecognitionUsageDescription':'Преобразуйте вашу речь в текст заметки. Если распознавание на устройстве недоступно, звук могут обрабатывать службы Apple через интернет.',
 'NSCalendarsFullAccessUsageDescription':'Показывайте события и дни рождения из календарей iPhone рядом со сроками проектов. Приложение не изменяет ваши календари.',
}
en={
 'CFBundleDisplayName':'Organizer Pro',
 'NSCameraUsageDescription':'Take photos to attach to a note or project you choose.',
 'NSMicrophoneUsageDescription':'Record voice attachments and dictate note text when you choose.',
 'NSSpeechRecognitionUsageDescription':'Turn your speech into note text. Apple services may process audio online when on-device recognition is unavailable.',
 'NSCalendarsFullAccessUsageDescription':'Display iPhone calendar events and birthdays alongside project due dates. The app does not change your calendars.',
}
info.update(ru)
(root/'Info.plist').write_bytes(plistlib.dumps(info,sort_keys=False))
def quote(s): return '"'+s.replace('\\','\\\\').replace('"','\\"')+'"'
for lang,strings in [('ru',ru),('en',en)]:
 d=root/(lang+'.lproj');d.mkdir(parents=True,exist_ok=True)
 (d/'InfoPlist.strings').write_text('\n'.join(quote(k)+' = '+quote(v)+';' for k,v in strings.items())+'\n')
privacy={'NSPrivacyTracking':False,'NSPrivacyTrackingDomains':[],'NSPrivacyCollectedDataTypes':[],
 'NSPrivacyAccessedAPITypes':[{'NSPrivacyAccessedAPIType':'NSPrivacyAccessedAPICategoryFileTimestamp','NSPrivacyAccessedAPITypeReasons':['C617.1']}]}
(root/'PrivacyInfo.xcprivacy').write_bytes(plistlib.dumps(privacy,sort_keys=False))
print('Generated RU/EN identity, purpose strings and privacy manifest')
