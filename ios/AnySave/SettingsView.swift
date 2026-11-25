import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var server = ""
    @State private var token = ""
    @State private var autoSave = true
    @State private var connections = 16.0
    @State private var result: String?
    @State private var testing = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("AnySave on your iPhone uses your computer to do the downloading, then copies the file here.")
                    Text("On your computer, open the anysave/web folder and run **./start.sh --lan**. It prints a server address and an access code. Keep the iPhone on the same Wi-Fi.")
                        .foregroundStyle(.secondary)
                }
                Section("Server") {
                    TextField("http://192.168.1.20:8750", text: $server)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Access code", text: $token)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    Button(testing ? "Testing…" : "Test connection") {
                        testing = true; result = nil
                        Task { result = await model.testConnection(server: server, token: token); testing = false }
                    }
                    .disabled(server.isEmpty || testing)
                    if let result {
                        Text(result).foregroundStyle(result.hasPrefix("Connected") ? .green : .red)
                    }
                }
                Section {
                    Toggle("Save to iPhone automatically", isOn: $autoSave)
                    VStack(alignment: .leading) {
                        Text("Parallel connections: \(Int(connections))")
                        Slider(value: $connections, in: 1...32, step: 1)
                    }
                } footer: {
                    Text("Finished downloads go to the Files app under On My iPhone › AnySave. From there you can also save videos to Photos.")
                }
                Section("Share sheet shortcut") {
                    Text("To send links from Instagram, TikTok and other apps straight to AnySave: in the Shortcuts app, make a shortcut that shows in the Share Sheet, receives URLs, and opens the URL **anysave://open?url=** followed by the Shortcut Input.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(model.configured ? "Settings" : "Set up AnySave")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.server = server.trimmingCharacters(in: .whitespaces)
                        model.token = token.trimmingCharacters(in: .whitespaces).uppercased()
                        model.autoSave = autoSave
                        model.connections = Int(connections)
                        model.startPolling()
                        dismiss()
                    }
                    .disabled(server.isEmpty)
                }
                if model.configured {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                }
            }
            .onAppear {
                server = model.server; token = model.token
                autoSave = model.autoSave; connections = Double(model.connections)
            }
        }
    }
}
