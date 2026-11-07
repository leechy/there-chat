import CoreLocation
import MapKit
import SwiftUI

struct MapHomeView: View {
    @State private var model = MapModel()
    @State private var position: MapCameraPosition = .userLocation(
        fallback: .region(.init(center: .init(latitude: 48.8530, longitude: 2.3499),
                                latitudinalMeters: 1200, longitudinalMeters: 1200)))
    @State private var openCluster: ChatCluster?
    @State private var openChat: Chat?
    @State private var locationSession: CLServiceSession?
    @Namespace private var mapScope

    var body: some View {
        Map(position: $position, scope: mapScope) {
            UserAnnotation()
            ForEach(model.clusters) { cluster in
                Annotation(cluster.chats.count == 1 ? cluster.chats[0].title : "\(cluster.chats.count) chats",
                           coordinate: cluster.coordinate, anchor: .bottom) {
                    ChatMarker(cluster: cluster) {
                        if cluster.chats.count == 1 {
                            openChat = cluster.chats[0]
                        } else {
                            openCluster = cluster
                        }
                    }
                    .popover(item: clusterBinding(cluster)) { cluster in
                        ClusterList(cluster: cluster) { chat in
                            openCluster = nil
                            openChat = chat
                        }
                        .presentationCompactAdaptation(.popover)
                    }
                }
                .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .mapControls {}
        .onMapCameraChange(frequency: .onEnd) { context in
            model.regionChanged(context.region)
        }
        .overlay(alignment: .topLeading) { topBar }
        .overlay(alignment: .trailing) { zoomControls }
        .overlay(alignment: .bottom) { bottomBar }
        .mapScope(mapScope)
        .toolbarVisibility(.hidden, for: .navigationBar)
        .navigationDestination(item: $openChat) { chat in
            Text("\(chat.emoji) \(chat.title)")
                .navigationTitle(chat.title)
        }
        .task {
            // Asks for "when in use" permission the first time and keeps it
            // active while the map is on screen.
            locationSession = CLServiceSession(authorization: .whenInUse)
        }
    }

    private func clusterBinding(_ cluster: ChatCluster) -> Binding<ChatCluster?> {
        Binding(
            get: { openCluster == cluster ? cluster : nil },
            set: { if $0 == nil { openCluster = nil } })
    }

    // MARK: Overlays

    private var topBar: some View {
        HStack {
            Button("Settings", systemImage: "gearshape") {}
            Spacer()
            Button("Notifications", systemImage: "bell") {}
        }
        .labelStyle(.iconOnly)
        .font(.title3)
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(.large)
        .padding(.horizontal)
    }

    private var zoomControls: some View {
        GlassEffectContainer(spacing: 12) {
            VStack(spacing: 12) {
                VStack(spacing: 0) {
                    Button("Zoom In", systemImage: "plus") { zoom(by: 0.5) }
                        .frame(width: 44, height: 44)
                    ZoomScale(progress: model.zoomProgress)
                        .frame(width: 6, height: 64)
                        .padding(.vertical, 4)
                    Button("Zoom Out", systemImage: "minus") { zoom(by: 2) }
                        .frame(width: 44, height: 44)
                }
                .labelStyle(.iconOnly)
                .glassEffect(.regular.interactive(), in: .capsule)

                MapUserLocationButton(scope: mapScope)
                    .buttonBorderShape(.circle)
            }
        }
        .padding(.trailing)
    }

    private var bottomBar: some View {
        VStack(spacing: 12) {
            if model.tooFar {
                Label("Zoom in to see chats", systemImage: "plus.magnifyingglass")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect(.regular, in: .capsule)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            } else if let error = model.error {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect(.regular, in: .capsule)
            }
            HStack {
                Button("New Chat", systemImage: "plus.bubble") {}
                    .buttonStyle(.glassProminent)
                Spacer()
                Button("My Chats", systemImage: "bubble.left.and.bubble.right") {}
                    .buttonStyle(.glass)
            }
            .controlSize(.large)
            .font(.headline)
        }
        .padding(.horizontal)
        .animation(.default, value: model.tooFar)
    }

    private func zoom(by factor: Double) {
        guard let region = model.region else { return }
        let span = MKCoordinateSpan(latitudeDelta: min(region.span.latitudeDelta * factor, 120),
                                    longitudeDelta: min(region.span.longitudeDelta * factor, 120))
        withAnimation {
            position = .region(.init(center: region.center, span: span))
        }
    }
}

/// Vertical scale next to the zoom buttons. The accent part marks the zoom
/// range where chats are visible; the dot is the current zoom.
private struct ZoomScale: View {
    let progress: Double

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let visibleFrom = h * 0.55 // threshold sits a bit below the middle
            let dotY = progress < 0
                ? visibleFrom + (h - visibleFrom) * min(-progress, 1)
                : visibleFrom * (1 - min(progress, 1))
            ZStack(alignment: .top) {
                Capsule().fill(.secondary.opacity(0.3))
                Capsule().fill(Color.accentColor).frame(height: visibleFrom)
                Circle()
                    .fill(.primary)
                    .frame(width: 8, height: 8)
                    .offset(y: dotY - 4)
            }
        }
        .accessibilityElement()
        .accessibilityLabel(progress < 0 ? "Chats hidden at this zoom" : "Chats visible at this zoom")
    }
}

private struct ChatMarker: View {
    let cluster: ChatCluster
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: -1) {
                Group {
                    if cluster.chats.count == 1 {
                        Text(cluster.chats[0].emoji).font(.title3)
                    } else {
                        Text("\(cluster.chats.count)").font(.headline).foregroundStyle(Color.accentColor)
                    }
                }
                .frame(minWidth: 36, minHeight: 36)
                .background(.background, in: .rect(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.accentColor, lineWidth: 2.5))
                Triangle()
                    .fill(Color.accentColor)
                    .frame(width: 10, height: 7)
            }
        }
        .buttonStyle(.plain)
    }
}

private struct Triangle: Shape {
    func path(in r: CGRect) -> Path {
        Path { p in
            p.move(to: .init(x: r.minX, y: r.minY))
            p.addLine(to: .init(x: r.maxX, y: r.minY))
            p.addLine(to: .init(x: r.midX, y: r.maxY))
            p.closeSubpath()
        }
    }
}

private struct ClusterList: View {
    let cluster: ChatCluster
    let select: (Chat) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(cluster.chats) { chat in
                    Button { select(chat) } label: {
                        HStack(spacing: 10) {
                            Text(chat.emoji).font(.title3)
                            Text(chat.title).foregroundStyle(Color.thereInk)
                            Spacer(minLength: 0)
                            if chat.kind == .channel {
                                Image(systemName: "megaphone").foregroundStyle(Color.thereSecondary)
                            }
                        }
                        .padding(.vertical, 10)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
            .padding(.horizontal)
        }
        .frame(width: 280)
        .frame(maxHeight: 320)
    }
}

#Preview {
    NavigationStack { MapHomeView() }
}
