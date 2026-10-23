import SwiftUI
import UIKit
import Combine

/// A separate window covers navigation sheets and attachment viewers as well as the root view.
@MainActor final class PrivacyGate: ObservableObject {
    private var window: UIWindow?
    private weak var previous: UIWindow?
    private weak var model: AppModel?
    private var active = true
    private var subscription: AnyCancellable?
    func attach(_ model: AppModel) {
        guard window == nil, let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        self.model = model
        let cover = UIWindow(windowScene: scene); cover.windowLevel = .statusBar + 10
        cover.rootViewController = UIHostingController(rootView: GateScreen().environmentObject(model)); cover.backgroundColor = UIColor(Brand.header(false))
        window = cover
        subscription = model.$locked.receive(on: RunLoop.main).sink { [weak self] _ in DispatchQueue.main.async { self?.refresh() } }
        refresh()
    }
    func phase(_ phase: ScenePhase) { active = phase == .active; if !active { model?.lock() }; refresh() }
    private func refresh() {
        guard let window, let model else { return }
        if !active || model.locked {
            if window.isHidden { previous = window.windowScene?.windows.first { $0.isKeyWindow && $0 !== window }; window.makeKeyAndVisible() }
        } else { window.isHidden = true; previous?.makeKey() }
    }
}
struct GateScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var phase
    @State private var pin = ""
    @State private var error = false
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    @State private var retry = 0
    var body: some View {
        ZStack {
            Brand.background(model.dark).ignoresSafeArea()
            ScrollView {
                VStack(spacing: 22) {
                    Image("BrandIcon").resizable().scaledToFit().frame(width: 88, height: 88).accessibilityHidden(true)
                    Text(model.t("Органайзер Про", "Organizer Pro")).font(.title2.bold())
                    if model.locked {
                        if model.pinUnavailable {
                            Text(model.t("Защищённое хранилище PIN-кода недоступно. Данные сохранены. Разблокируйте iPhone и повторите попытку.", "The protected PIN storage is unavailable. Your data is safe. Unlock your iPhone and try again.")).multilineTextAlignment(.center)
                            Button(model.t("Повторить", "Try again")) { model.refreshPIN() }.buttonStyle(Pill(bold: model.bold))
                        } else {
                            SecureField(model.t("PIN-код", "PIN code"), text: $pin).keyboardType(.numberPad).textContentType(.password).textFieldStyle(.roundedBorder).accessibilityIdentifier("unlockPIN")
                                .onChange(of: pin) { _, value in pin = String(value.filter { $0.isASCII && $0.isNumber }.prefix(12)) }
                            if retry > 0 { Text(model.t("Повторите через \(retry) с", "Try again in \(retry)s")) }
                            else if error { Text(model.t("Неверный PIN-код", "Incorrect PIN code")).foregroundStyle(.red) }
                            Button(model.t("Открыть", "Unlock")) { if model.unlock(pin) { pin = ""; error = false } else { error = true; pin = "" }; retry = (try? model.vault.retrySeconds()) ?? 30 }.buttonStyle(Pill(bold: model.bold)).disabled(pin.count < 4 || retry > 0).accessibilityIdentifier("unlockButton")
                        }
                    }
                }.padding(28).frame(maxWidth: 460).frame(maxWidth: .infinity).padding(.top, 60)
            }
        }.tint(Brand.accent(model.dark)).preferredColorScheme(model.dark ? .dark : .light)
            .onReceive(clock) { _ in retry = (try? model.vault.retrySeconds()) ?? 30 }
    }
}
