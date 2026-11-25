import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SiteTabs()
                    PickerSection()
                    if let info = model.info { InfoCard(info: info) }
                    DownloadsSection()
                    Text("Only download videos you own or have permission to download. Private posts, login-only content and DRM-protected videos are not supported.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("AnySave")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .overlay(alignment: .bottom) { Toast() }
            .onAppear {
                if !model.configured { showSettings = true }
                model.startPolling()
            }
        }
    }
}

struct SiteTabs: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(sites) { s in
                        let on = s.id == model.tab.id
                        Button { model.select(s, clear: true) } label: {
                            HStack(spacing: 6) {
                                Circle().fill(s.color).frame(width: 8, height: 8)
                                Text(s.name).font(.subheadline.weight(on ? .semibold : .regular))
                            }
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(Capsule().fill(Color(.secondarySystemBackground)))
                            .overlay(Capsule().strokeBorder(on ? s.color : Color(.separator), lineWidth: on ? 2 : 1))
                        }
                        .buttonStyle(.plain)
                        .id(s.id)
                    }
                }
                .padding(.horizontal, 2).padding(.vertical, 2)
            }
            .onChange(of: model.tab) { t in withAnimation { proxy.scrollTo(t.id, anchor: .center) } }
        }
    }
}

struct PickerSection: View {
    @EnvironmentObject var model: AppModel
    @FocusState private var focused: Bool

    var body: some View {
        let s = model.tab
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 10).fill(s.color).frame(width: 40, height: 40)
                    .overlay(Text(s.id == "all" ? "★" : String(s.name.prefix(2))).font(.headline).foregroundStyle(.white))
                VStack(alignment: .leading) {
                    Text(s.name).font(.title3.weight(.semibold))
                    Text(s.subtitle).font(.subheadline).foregroundStyle(.secondary)
                }
            }

            if s.id == "whatsapp" {
                VStack(alignment: .leading, spacing: 6) {
                    Text("WhatsApp videos can't be downloaded from a link.").font(.headline)
                    Text("They're end-to-end encrypted inside your chats and have no public URL. To save one: open the chat, tap the video, then tap Share and choose Save. WhatsApp Channels videos work the same way.")
                        .font(.subheadline)
                }
                .padding().frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
            } else {
                HStack {
                    TextField(s.placeholder, text: $model.url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.go).focused($focused)
                        .onSubmit { model.lookup() }
                        .onChange(of: model.url) { _ in model.checkMatch() }
                    if !model.url.isEmpty {
                        Button { model.url = ""; model.mismatch = nil } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                    }
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))

                HStack(spacing: 10) {
                    PasteButton(payloadType: String.self) { strings in
                        Task { @MainActor in model.receive(strings.first) }
                    }
                    .labelStyle(.titleAndIcon)
                    Button {
                        focused = false; model.lookup()
                    } label: {
                        HStack {
                            if model.loading { ProgressView().tint(.white) }
                            Text(model.loading ? "Looking up…" : "Get video").bold()
                        }
                        .frame(maxWidth: .infinity, minHeight: 34)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.loading || model.url.isEmpty)
                }

                if let m = model.mismatch {
                    HStack {
                        Text("This looks like a \(m.name) link.").font(.subheadline)
                        Spacer()
                        Button("Switch to \(m.name)") { model.switchToMismatch() }.font(.subheadline.bold())
                    }
                }
                if let e = model.error {
                    Text(e).font(.subheadline).foregroundStyle(.red)
                }
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(s.tips, id: \.self) { Text("•  \($0)").font(.footnote).foregroundStyle(.secondary) }
                }
            }
        }
    }
}

struct Chip: View {
    let title: String
    let on: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(on ? .semibold : .regular))
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(on ? Color.accentColor.opacity(0.15) : Color(.secondarySystemBackground)))
                .overlay(Capsule().strokeBorder(on ? Color.accentColor : Color(.separator)))
        }
        .buttonStyle(.plain)
    }
}

struct InfoCard: View {
    @EnvironmentObject var model: AppModel
    let info: VideoInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                if let t = info.thumbnail, let u = URL(string: t) {
                    AsyncImage(url: u) { img in img.resizable().scaledToFill() } placeholder: { Color(.tertiarySystemFill) }
                        .frame(width: 120, height: 68).clipShape(RoundedRectangle(cornerRadius: 8))
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(info.title ?? "Untitled").font(.headline).lineLimit(3)
                    Text([info.site, info.uploader, formatDuration(info.duration)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            Picker("What to download", selection: $model.mode) {
                Text("Video + Audio").tag("both")
                Text("Video only").tag("video")
                Text("Audio only").tag("audio")
            }
            .pickerStyle(.segmented)

            if model.mode != "audio" {
                if info.qualities.isEmpty {
                    Text("Only one quality is available for this video.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    Text("Quality").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(info.qualities, id: \.height) { q in
                                let size = model.size(for: q)
                                Chip(title: q.label + ((q.hdr ?? false) ? " HDR" : "") + ((size ?? 0) > 0 ? " · ~\(formatBytes(size))" : ""),
                                     on: model.height == q.height) { model.height = q.height }
                            }
                        }
                    }
                }
                Toggle(isOn: $model.compat) {
                    VStack(alignment: .leading) {
                        Text("Most compatible (H.264 MP4)").font(.subheadline)
                        Text("Plays in Photos and everywhere. Some sites only offer it up to 1080p.").font(.caption).foregroundStyle(.secondary)
                    }
                }
            } else {
                Text("Format").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                HStack { ForEach(["mp3", "m4a", "opus", "wav", "flac"], id: \.self) { f in Chip(title: f.uppercased(), on: model.audioFormat == f) { model.audioFormat = f } } }
                if !["wav", "flac"].contains(model.audioFormat) {
                    Text("Bitrate (kbps)").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    HStack { ForEach(["320", "256", "192", "128", "64"], id: \.self) { b in Chip(title: b, on: model.audioBitrate == b) { model.audioBitrate = b } } }
                }
            }

            Button { model.startDownload() } label: {
                Label("Download", systemImage: "arrow.down.circle.fill").bold().frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(.systemBackground)).shadow(color: .black.opacity(0.08), radius: 10, y: 4))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Color(.separator).opacity(0.5)))
    }
}

struct DownloadsSection: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Downloads").font(.headline)
                Text("\(model.jobs.count)").foregroundStyle(.secondary)
                Spacer()
                if model.jobs.contains(where: { !$0.isActive }) {
                    Button("Clear finished") { model.clearFinished() }.font(.subheadline)
                }
            }
            if model.jobs.isEmpty {
                Text("Your downloads show up here with live progress. Finished files are saved to Files › On My iPhone › AnySave.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(model.jobs) { JobRow(job: $0) }
        }
        .padding(.top, 8)
    }
}

struct JobRow: View {
    @EnvironmentObject var model: AppModel
    let job: Job

    var statusText: String {
        switch job.status {
        case "queued": return "Waiting"
        case "preparing": return "Preparing"
        case "downloading":
            var s = "\(formatBytes(job.done)) of \(formatBytes(job.total))"
            if let sp = job.speed, sp > 1 { s += " · \(formatBytes(sp))/s" }
            if let e = job.eta, e > 0 { s += " · \(formatDuration(e)) left" }
            return s
        case "processing": return job.message ?? "Finishing"
        case "done": return (model.savedFiles[job.id] != nil ? "On this iPhone · " : "Done on computer · ") + (job.label ?? "")
        case "error": return job.error ?? "Failed"
        case "cancelled": return "Cancelled"
        default: return job.status
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            if let t = job.thumbnail, let u = URL(string: t) {
                AsyncImage(url: u) { img in img.resizable().scaledToFill() } placeholder: { Color(.tertiarySystemFill) }
                    .frame(width: 72, height: 40).clipShape(RoundedRectangle(cornerRadius: 6))
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(job.title ?? "Video").font(.subheadline.weight(.medium)).lineLimit(1)
                Text(statusText).font(.caption).lineLimit(2).foregroundStyle(job.status == "error" ? .red : .secondary)
                if job.isActive {
                    if let p = job.progress { ProgressView(value: p) } else { ProgressView(value: nil as Double?).progressViewStyle(.linear) }
                }
            }
            Spacer(minLength: 0)
            if job.isActive {
                Button { model.cancel(job) } label: { Image(systemName: "xmark.circle") }
            } else {
                if job.status == "done" {
                    if let file = model.savedFiles[job.id] {
                        Menu {
                            ShareLink(item: file) { Label("Share or save to Files", systemImage: "square.and.arrow.up") }
                            if ["mp4", "mov", "m4v"].contains(file.pathExtension.lowercased()) {
                                Button { model.saveToPhotos(file) } label: { Label("Save to Photos", systemImage: "photo") }
                            }
                        } label: { Image(systemName: "square.and.arrow.up") }
                    } else {
                        Button { Task { await model.save(job) } } label: { Image(systemName: "arrow.down.to.line") }
                    }
                }
                Button(role: .destructive) { model.remove(job) } label: { Image(systemName: "trash") }
            }
        }
        .buttonStyle(.borderless)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Color(.separator).opacity(0.6)))
    }
}

struct Toast: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        if let t = model.toast {
            Text(t).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                .background(Capsule().fill(.regularMaterial)).padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: t) {
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    withAnimation { model.toast = nil }
                }
        }
    }
}
