import SwiftUI

@main struct OrganizerProApp: App {
    @UIApplicationDelegateAdaptor(OrganizerAppDelegate.self) private var delegate
    @StateObject private var model = AppModel()
    @StateObject private var gate = PrivacyGate()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
                .preferredColorScheme(model.dark ? .dark : .light)
                .environment(\.locale, model.locale)
                .onAppear { gate.attach(model); model.clearTemporaryFiles() }
                .onOpenURL { model.importBackup($0) }
                .onChange(of: phase) { _, next in gate.phase(next); if next == .active { model.reschedule() } }
        }
    }
}
