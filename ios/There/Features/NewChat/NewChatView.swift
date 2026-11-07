import MapKit
import SwiftUI

/// Drag the map to place the chat, fill in the sheet, create.
struct NewChatView: View {
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var position: MapCameraPosition = .userLocation(
        fallback: .region(.init(center: .init(latitude: 48.8530, longitude: 2.3499),
                                latitudinalMeters: 600, longitudinalMeters: 600)))
    @State private var center: CLLocationCoordinate2D?
    @State private var form = NewChatForm()
    @State private var detent: PresentationDetent = .medium
    @State private var busy = false
    @State private var error: String?

    /// The pin sits in the upper part of the screen, above the sheet.
    private static let pinFraction: CGFloat = 0.27

    var body: some View {
        GeometryReader { geo in
            let pinPoint = CGPoint(x: geo.size.width / 2, y: geo.size.height * Self.pinFraction)
            MapReader { proxy in
                Map(position: $position) {
                    UserAnnotation()
                }
                .mapStyle(.standard(pointsOfInterest: .excludingAll))
                .onMapCameraChange(frequency: .continuous) { _ in
                    center = proxy.convert(pinPoint, from: .local)
                }
                .overlay(alignment: .topLeading) {
                    pin.position(x: pinPoint.x, y: pinPoint.y - 24)
                }
            }
        }
        .ignoresSafeArea()
        .overlay(alignment: .top) {
            HStack {
                Button("Close", systemImage: "xmark") { dismiss() }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.circle)
                    .controlSize(.large)
                Spacer()
                Text("Drag the map to place the chat")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .glassEffect(.regular, in: .capsule)
                Spacer()
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.horizontal)
        }
        .sheet(isPresented: .constant(true)) {
            NewChatSheet(form: $form, busy: busy, error: error, create: create)
                .presentationDetents([.height(140), .medium, .large], selection: $detent)
                .presentationBackgroundInteraction(.enabled(upThrough: .medium))
                .interactiveDismissDisabled()
                .tint(Color.accentColor)
        }
    }

    private var pin: some View {
        VStack(spacing: -1) {
            Text(form.emoji.isEmpty ? "❓" : form.emoji)
                .font(.title3)
                .frame(width: 40, height: 40)
                .background(.background, in: .rect(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.accentColor, lineWidth: 2.5))
            Image(systemName: "arrowtriangle.down.fill")
                .font(.system(size: 10))
                .foregroundStyle(Color.accentColor)
        }
        .allowsHitTesting(false)
    }

    private func create() {
        guard let center, form.isValid, !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let chat = try await APIClient.shared.createChat(.init(
                    kind: form.kind, title: form.trimmedTitle, emoji: form.emoji,
                    lat: center.latitude, lng: center.longitude,
                    freeJoin: form.freeJoin, premoderated: form.premoderated, visible: form.visible,
                    name: form.trimmedName))
                dismiss()
                router.open(.chat(chat))
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct NewChatForm {
    var emoji = ""
    var title = ""
    var kind: ChatKind = .chat
    var freeJoin = true
    var premoderated = false
    var visible = true
    var name = ""

    var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }
    var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    var isValid: Bool { !emoji.isEmpty && !trimmedTitle.isEmpty && !trimmedName.isEmpty }
}

private struct NewChatSheet: View {
    @Binding var form: NewChatForm
    let busy: Bool
    let error: String?
    let create: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(spacing: 2) {
                            EmojiField(emoji: $form.emoji)
                                .frame(width: 52, height: 52)
                                .background(Color.thereSurfaceMuted, in: .rect(cornerRadius: 10))
                            Text("Icon").font(.caption2).foregroundStyle(Color.thereSecondary)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            TextField("Guess who?", text: $form.title)
                                .font(.title3)
                                .padding(.horizontal, 12)
                                .frame(height: 52)
                                .background(Color.thereSurfaceMuted, in: .rect(cornerRadius: 10))
                                .onChange(of: form.title) { _, t in if t.count > 32 { form.title = String(t.prefix(32)) } }
                            HStack {
                                Text("Name: keep it short")
                                Spacer()
                                Text("\(form.title.count)/32")
                            }
                            .font(.caption2)
                            .foregroundStyle(Color.thereSecondary)
                        }
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(.init())
                }

                Section {
                    Picker("Type", selection: $form.kind) {
                        Text("Chat").tag(ChatKind.chat)
                        Text("Channel").tag(ChatKind.channel)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(.init())
                } footer: {
                    Text(form.kind == .chat
                         ? "All members of the chat can post new messages."
                         : "Only admins post. Everyone else reads and reacts.")
                }

                Section {
                    Toggle(isOn: $form.freeJoin) {
                        Text("Free to Join")
                        Text("Anyone who can see the chat or has a link can join and read all messages.")
                    }
                    if form.kind == .chat {
                        Toggle(isOn: $form.premoderated) {
                            Text("Premoderated")
                            Text("New messages are hidden until an admin publishes them.")
                        }
                    }
                    Toggle(isOn: $form.visible) {
                        Text("Visible to Everyone")
                        Text("Shown on the map when zoomed in to building level. Hidden chats are reachable only by link.")
                    }
                }

                Section {
                    NameField(name: $form.name)
                } header: {
                    Text("Your name in this chat")
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(Color.thereDanger)
                    }
                }
            }
            .navigationTitle(form.kind == .chat ? "New Chat" : "New Channel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create", systemImage: "checkmark", action: create)
                        .disabled(!form.isValid || busy)
                }
            }
        }
    }
}

/// Name field prefilled with a random film character.
private struct NameField: View {
    @Binding var name: String

    var body: some View {
        HStack {
            TextField("Your name", text: $name)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
            Button("Random name", systemImage: "shuffle") {
                Task { name = (try? await APIClient.shared.suggestedNames(count: 1).first) ?? name }
            }
            .labelStyle(.iconOnly)
        }
        .task {
            if name.isEmpty { name = (try? await APIClient.shared.suggestedNames(count: 1).first) ?? "" }
        }
    }
}

/// A text field that opens the emoji keyboard and keeps a single emoji.
struct EmojiField: UIViewRepresentable {
    @Binding var emoji: String

    func makeUIView(context: Context) -> EmojiTextField {
        let field = EmojiTextField()
        field.textAlignment = .center
        field.font = .systemFont(ofSize: 30)
        field.tintColor = .clear
        field.placeholder = "❓"
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        return field
    }

    func updateUIView(_ field: EmojiTextField, context: Context) {
        if field.text != emoji { field.text = emoji }
    }

    func makeCoordinator() -> Coordinator { Coordinator(emoji: $emoji) }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var emoji: Binding<String>
        init(emoji: Binding<String>) { self.emoji = emoji }

        @MainActor @objc func changed(_ field: UITextField) {
            // Keep only the last emoji typed.
            let last = (field.text ?? "").reversed().first { $0.unicodeScalars.first?.properties.isEmojiPresentation == true
                || $0.unicodeScalars.contains { $0.properties.isEmoji && $0.value > 0x238C } }
            let value = last.map(String.init) ?? ""
            field.text = value
            emoji.wrappedValue = value
            if !value.isEmpty { field.resignFirstResponder() }
        }
    }
}

final class EmojiTextField: UITextField {
    override var textInputMode: UITextInputMode? {
        UITextInputMode.activeInputModes.first { $0.primaryLanguage == "emoji" } ?? super.textInputMode
    }
}
