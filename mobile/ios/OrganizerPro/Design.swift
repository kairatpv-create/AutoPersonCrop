import SwiftUI
import OrganizerCore

enum Brand {
    static let blue = Color(hex: 0x0A84D8)
    static func accent(_ dark: Bool) -> Color { dark ? Color(hex: 0x5AC8FA) : blue }
    static let yellow = Color(hex: 0xFFD43B)
    static let ink = Color(hex: 0x082D42)
    static func background(_ dark: Bool) -> Color { Color(hex: dark ? 0x06151D : 0xF4FBFF) }
    static func header(_ dark: Bool) -> Color { Color(hex: dark ? 0x123C50 : 0xCDEFFF) }
    static func card(_ dark: Bool, color: String = "blue") -> Color {
        let light: [String: UInt] = ["blue":0xD9F2FF,"yellow":0xFFF1A8,"bright_yellow":0xFFE86B,"green":0xDDF4D9,"orange":0xFFE6CC,"pink":0xFFE2ED,"purple":0xEDE1FF,"red":0xFFDFDF,"cyan":0xCEFAFC]
        let deep: [String: UInt] = ["blue":0x0A4968,"yellow":0x5A4700,"bright_yellow":0x665000,"green":0x1F4E35,"orange":0x5A3923,"pink":0x563043,"purple":0x44365C,"red":0x5A2C2C,"cyan":0x164F54]
        return Color(hex: (dark ? deep : light)[color] ?? (dark ? 0x0A4968 : 0xD9F2FF))
    }
    static let colors = ["blue", "yellow", "green", "orange", "pink", "purple", "red", "bright_yellow", "cyan"]
}
extension Color {
    init(hex: UInt) { self.init(.sRGB, red: Double((hex >> 16) & 255)/255, green: Double((hex >> 8) & 255)/255, blue: Double(hex & 255)/255, opacity: 1) }
}
struct UserText: View {
    @EnvironmentObject private var model: AppModel
    @ScaledMetric(relativeTo: .body) private var point = 16
    let text: String
    var strong = false
    var lines: Int? = nil
    init(_ text: String, strong: Bool = false, lines: Int? = nil) { self.text = text; self.strong = strong; self.lines = lines }
    var body: some View { Text(text).font(.system(size: point * model.contentScale, weight: strong ? (model.bold ? .bold : .semibold) : model.bold ? .semibold : .regular)).lineLimit(lines).fixedSize(horizontal: false, vertical: true) }
}
struct Card<Content: View>: View {
    @EnvironmentObject private var model: AppModel
    var color = "blue"
    @ViewBuilder var content: Content
    var body: some View { VStack(alignment: .leading, spacing: 10) { content }.frame(maxWidth: .infinity, alignment: .leading).padding(14).background(Brand.card(model.dark, color: color), in: RoundedRectangle(cornerRadius: 16)) }
}
struct Pill: ButtonStyle {
    var bold = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.fontWeight(bold ? .bold : .semibold).foregroundStyle(Brand.ink).padding(.horizontal, 14).padding(.vertical, 12).frame(minHeight: 44).background(Brand.yellow, in: RoundedRectangle(cornerRadius: 22)).opacity(configuration.isPressed ? 0.7 : 1)
    }
}
struct ColorPickerRow: View {
    @EnvironmentObject private var model: AppModel
    @Binding var selected: String
    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(Array(Brand.colors.enumerated()), id: \.element) { i, key in
                    Button { selected = key } label: {
                        ZStack { Circle().fill(Brand.card(false, color: key)); if selected == key { Image(systemName: "checkmark").foregroundStyle(Brand.ink) } }.frame(width: 40, height: 40)
                    }.accessibilityLabel(model.t("Цвет \(i + 1)", "Color \(i + 1)")).accessibilityAddTraits(selected == key ? [.isSelected] : [])
                }
            }
        }.scrollIndicators(.hidden)
    }
}
struct Page<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { ScrollView { LazyVStack(alignment: .leading, spacing: 12) { content }.padding(12) }.scrollDismissesKeyboard(.interactively) }
}
struct EmptyCard: View {
    let title: String; let symbol: String
    var body: some View { ContentUnavailableView(title, systemImage: symbol).padding(.top, 45) }
}
extension Date {
    var milliseconds: Int64 { Int64(timeIntervalSince1970 * 1000) }
    init(milliseconds: Int64) { self.init(timeIntervalSince1970: Double(milliseconds) / 1000) }
}
