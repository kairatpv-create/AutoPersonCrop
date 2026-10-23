#!/usr/bin/env python3
"""Deterministic Xcode project generator. No CocoaPods, XcodeGen or external project tooling."""
from pathlib import Path
import hashlib, json

root=Path(__file__).resolve().parents[2]/'mobile/ios'
objects={}
def ident(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def obj(label,isa,**values):
    key=ident(label); objects[key]={'isa':isa,**values}; return key
def config_list(name,values):
    configs=[]
    for configuration in ['Debug','Release']:
        settings={**values}
        settings.update({'SWIFT_OPTIMIZATION_LEVEL':'-Onone' if configuration=='Debug' else '-O','DEBUG_INFORMATION_FORMAT':'dwarf' if configuration=='Debug' else 'dwarf-with-dsym'})
        if configuration=='Debug': settings.update({'SWIFT_ACTIVE_COMPILATION_CONDITIONS':'DEBUG $(inherited)','ENABLE_TESTABILITY':'YES','ONLY_ACTIVE_ARCH':'YES'})
        configs.append(obj(name+configuration,'XCBuildConfiguration',name=configuration,buildSettings=settings))
    return obj(name+'configs','XCConfigurationList',buildConfigurations=configs,defaultConfigurationIsVisible=0,defaultConfigurationName='Release')
def file(path,kind,tree='<group>'):
    return obj('file:'+path,'PBXFileReference',lastKnownFileType=kind,path=path,sourceTree=tree)

product_group=obj('products','PBXGroup',name='Products',sourceTree='<group>',children=[])
groups=[]; targets=[]
package=obj('core package','XCLocalSwiftPackageReference',relativePath='OrganizerCore')
project_config=config_list('project',{'IPHONEOS_DEPLOYMENT_TARGET':'17.0','SDKROOT':'iphoneos','SWIFT_VERSION':'5.0','CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','ENABLE_USER_SCRIPT_SANDBOXING':'YES','GCC_WARN_INHIBIT_ALL_WARNINGS':'NO','SWIFT_STRICT_CONCURRENCY':'targeted','COPY_PHASE_STRIP':'NO'})
app_target_id=ident('target:OrganizerPro')
for name,kind in [('OrganizerPro','application'),('OrganizerProTests','bundle.unit-test'),('OrganizerProUITests','bundle.ui-testing')]:
    source_files=[file(str(p.relative_to(root)),'sourcecode.swift') for p in sorted((root/name).glob('*.swift'))]
    resources=[]
    if name=='OrganizerPro':
        resources=[file(name+'/Assets.xcassets','folder.assetcatalog'),file(name+'/PrivacyInfo.xcprivacy','text.xml')]
        localized=[file(name+'/'+lang+'.lproj/InfoPlist.strings','text.plist.strings') for lang in ['ru','en']]
        for key,lang in zip(localized,['ru','en']): objects[key]['name']=lang
        resources.append(obj('info localization','PBXVariantGroup',name='InfoPlist.strings',sourceTree='<group>',children=localized))
    else: resources=[file('OrganizerCore/Tests/OrganizerCoreTests/Fixtures','folder')]
    groups.append(obj('group:'+name,'PBXGroup',name=name,children=source_files+resources,sourceTree='<group>'))
    sources=obj(name+'sources','PBXSourcesBuildPhase',buildActionMask=2147483647,files=[obj(name+'build:'+f,'PBXBuildFile',fileRef=f) for f in source_files],runOnlyForDeploymentPostprocessing=0)
    resource_phase=obj(name+'resources','PBXResourcesBuildPhase',buildActionMask=2147483647,files=[obj(name+'resource:'+f,'PBXBuildFile',fileRef=f) for f in resources],runOnlyForDeploymentPostprocessing=0)
    products=[]; framework_files=[]
    if name!='OrganizerProUITests':
        products=[obj(name+'core product','XCSwiftPackageProductDependency',productName='OrganizerCore')]
        framework_files=[obj(name+'core framework','PBXBuildFile',productRef=products[0])]
    frameworks=obj(name+'frameworks','PBXFrameworksBuildPhase',buildActionMask=2147483647,files=framework_files,runOnlyForDeploymentPostprocessing=0)
    product=file(name+('.app' if kind=='application' else '.xctest'),'wrapper.application' if kind=='application' else 'wrapper.cfbundle','BUILT_PRODUCTS_DIR')
    objects[product_group]['children'].append(product)
    settings={'PRODUCT_NAME':'$(TARGET_NAME)','PRODUCT_BUNDLE_IDENTIFIER':'com.organizerpro.mobile'+('' if kind=='application' else '.'+name.lower()),'TARGETED_DEVICE_FAMILY':'1','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator','SUPPORTS_MACCATALYST':'NO','SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD':'NO','CODE_SIGN_STYLE':'Automatic','DEVELOPMENT_TEAM':'','MARKETING_VERSION':'1.0.0','CURRENT_PROJECT_VERSION':'1','GENERATE_INFOPLIST_FILE':'YES'}
    dependencies=[]
    if kind=='application': settings.update({'GENERATE_INFOPLIST_FILE':'NO','INFOPLIST_FILE':name+'/Info.plist','ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME':'AccentColor','SWIFT_EMIT_LOC_STRINGS':'YES','LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks'})
    else:
        proxy=obj(name+'proxy','PBXContainerItemProxy',containerPortal=ident('project'),proxyType=1,remoteGlobalIDString=app_target_id,remoteInfo='OrganizerPro')
        dependencies=[obj(name+'dependency','PBXTargetDependency',target=app_target_id,targetProxy=proxy)]
        settings.update({'LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks @loader_path/Frameworks'})
        if kind=='bundle.unit-test': settings.update({'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/OrganizerPro.app/OrganizerPro','BUNDLE_LOADER':'$(TEST_HOST)'})
        else: settings['TEST_TARGET_NAME']='OrganizerPro'
    target=obj('target:'+name,'PBXNativeTarget',name=name,productName=name,productReference=product,productType='com.apple.product-type.'+kind,buildConfigurationList=config_list(name,settings),buildPhases=[sources,frameworks,resource_phase],buildRules=[],dependencies=dependencies,packageProductDependencies=products)
    targets.append(target)

main=obj('main','PBXGroup',children=groups+[product_group],sourceTree='<group>')
project=obj('project','PBXProject',attributes={'BuildIndependentTargetsInParallel':'YES','LastUpgradeCheck':'2600','TargetAttributes':{app_target_id:{'CreatedOnToolsVersion':'26.0'}}},buildConfigurationList=project_config,compatibilityVersion='Xcode 14.0',developmentRegion='ru',hasScannedForEncodings=0,knownRegions=['ru','en','Base'],mainGroup=main,productRefGroup=product_group,projectDirPath='',projectRoot='',targets=targets,packageReferences=[package])
def render(value,level=0):
    pad='\t'*level
    if isinstance(value,dict): return '{\n'+''.join(pad+'\t'+json.dumps(str(k))+ ' = '+render(v,level+1)+';\n' for k,v in value.items())+pad+'}'
    if isinstance(value,list): return '(\n'+''.join(pad+'\t'+render(v,level+1)+',\n' for v in value)+pad+')'
    if isinstance(value,int): return str(value)
    return json.dumps(value,ensure_ascii=False)
folder=root/'OrganizerPro.xcodeproj';folder.mkdir(exist_ok=True)
(folder/'project.pbxproj').write_text('// !$*UTF8*$!\n'+render({'archiveVersion':1,'classes':{},'objectVersion':56,'objects':objects,'rootObject':project})+'\n')
schemes=folder/'xcshareddata/xcschemes';schemes.mkdir(parents=True,exist_ok=True)
def reference(name,extension): return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ident("target:"+name)}" BuildableName="{name}.{extension}" BlueprintName="{name}" ReferencedContainer="container:OrganizerPro.xcodeproj"/>'
(schemes/'OrganizerPro.xcscheme').write_text('<?xml version="1.0" encoding="UTF-8"?>\n<Scheme LastUpgradeVersion="2600" version="1.7">\n<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">'+reference('OrganizerPro','app')+'</BuildActionEntry></BuildActionEntries></BuildAction>\n<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>'+''.join('<TestableReference skipped="NO" parallelizable="NO">'+reference(n,'xctest')+'</TestableReference>' for n in ['OrganizerProTests','OrganizerProUITests'])+'</Testables></TestAction>\n<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">'+reference('OrganizerPro','app')+'</BuildableProductRunnable></LaunchAction>\n<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">'+reference('OrganizerPro','app')+'</BuildableProductRunnable></ProfileAction>\n<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>\n</Scheme>\n')
print('Generated OrganizerPro.xcodeproj with app, integration tests and UI tests')
