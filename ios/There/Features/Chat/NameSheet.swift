import SwiftUI

/// Picks the name to use in one chat, with random film character suggestions.
struct NameSheet: View {
    enum Purpose: String, Identifiable {
        case join, rename
        var id: String { rawValue }
    }

    let purpose: Purpose
    let chat: Chat
    let submit: (String) async throws -> Void

    @State private var name = ""
    @State private var suggestions: [String] = []
    @State private var error: String?
    @State private var busy = false
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(explanation)
                    .font(.subheadline)
                    .foregroundStyle(Color.thereSecondary)

                VStack(alignment: .leading, spacing: 4) {
                    TextField("Your name in this chat", text: $name)
                        .font(.title3)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit(save)
                        .padding(12)
                        .background(Color.thereSurfaceMuted, in: .rect(cornerRadius: 10))
                        .onChange(of: name) { _, new in if new.count > 32 { name = String(new.prefix(32)) } }
                    HStack {
                        if let error {
                            Text(error).foregroundStyle(Color.thereDanger)
                        }
                        Spacer()
                        Text("\(name.count)/32").foregroundStyle(Color.thereSecondary)
                    }
                    .font(.caption)
                }

                HStack {
                    Text("Or be someone famous:").font(.subheadline.weight(.medium))
                    Spacer()
                    Button("Shuffle", systemImage: "shuffle") { Task { await loadSuggestions() } }
                        .labelStyle(.iconOnly)
                }
                FlowLayout(spacing: 8) {
                    ForEach(suggestions, id: \.self) { suggestion in
                        Button(suggestion) { name = suggestion }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                    }
                }
                Spacer()
            }
            .padding()
            .navigationTitle(purpose == .join ? "Join \(chat.title)" : "Change Your Name")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", systemImage: "xmark") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(actionTitle, systemImage: "checkmark", action: save)
                        .disabled(trimmed.isEmpty || busy)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            if purpose == .rename { name = chat.me?.name ?? "" }
            focused = true
            await loadSuggestions()
        }
    }

    private var explanation: String {
        switch purpose {
        case .join:
            chat.freeJoin
                ? "Nobody here knows who you are. Pick any name — it's only used in this chat."
                : "The admins will see this name in your request. It's only used in this chat."
        case .rename:
            "Your old messages keep the old name. Everyone will see that you changed it."
        }
    }

    private var actionTitle: String {
        switch purpose {
        case .join: chat.freeJoin ? "Join" : "Send Request"
        case .rename: "Save"
        }
    }

    private func loadSuggestions() async {
        suggestions = (try? await APIClient.shared.suggestedNames(count: 6)) ?? suggestions
        if name.isEmpty, purpose == .join, let first = suggestions.first { name = first }
    }

    private func save() {
        guard !trimmed.isEmpty, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try await submit(trimmed)
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Wraps children onto new lines, like text.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0,
                      height: rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: .init(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for (i, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            if !rows[rows.count - 1].indices.isEmpty && rows[rows.count - 1].width + spacing + size.width > width {
                rows.append(Row())
            }
            var row = rows[rows.count - 1]
            row.width += (row.indices.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.indices.append(i)
            rows[rows.count - 1] = row
        }
        return rows
    }
}

/// Free-text abuse report for a message or a whole chat.
struct ReportSheet: View {
    let send: (String) async -> Void
    @State private var reason = ""
    @Environment(\.dismiss) private var dismiss

    private static let presets = ["Child safety", "Spam", "Harassment or hate", "Violence or threats", "Sexual content", "Illegal activity"]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(Self.presets, id: \.self) { preset in
                        Button {
                            reason = preset
                        } label: {
                            HStack {
                                Text(preset).foregroundStyle(Color.thereInk)
                                Spacer()
                                if reason == preset { Image(systemName: "checkmark") }
                            }
                        }
                    }
                } footer: {
                    Text("Reports go to the There team, not to the chat admins.")
                }
                Section("Details") {
                    TextField("What's wrong?", text: $reason, axis: .vertical).lineLimit(2...5)
                }
            }
            .navigationTitle("Report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", systemImage: "xmark") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send", systemImage: "checkmark") {
                        Task {
                            await send(reason)
                            dismiss()
                        }
                    }
                    .disabled(reason.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
