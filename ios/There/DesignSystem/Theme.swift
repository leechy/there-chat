import SwiftUI

/// Colors from the original There design (docs/UI-PLATFORMS.md).
/// The accent color lives in the asset catalog so system controls pick it up.
extension Color {
    static let thereInk = Color(light: 0x121E49, dark: 0xE6E9F5)
    static let thereSecondary = Color(light: 0x949BB4, dark: 0x8A91AA)
    static let thereSurfaceMuted = Color(light: 0xF2F4FA, dark: 0x1C2033)
    static let thereReaction = Color(light: 0xFFF9E4, dark: 0x3A3322)
    static let thereDanger = Color(light: 0xC41111, dark: 0xFF5A5A)
}

private extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}
