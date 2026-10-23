import SwiftUI
import PhotosUI
import CoreTransferable
import UniformTypeIdentifiers
import QuickLook
import AVFoundation
import OrganizerCore

struct ImportedImage: Transferable {
    let data: Data; let name: String; let mime: String
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { file in
            ImportedImage(data: try AppModel.readFile(file.file, limit: BackupCodec.maxAttachment), name: file.file.lastPathComponent, mime: UTType(filenameExtension: file.file.pathExtension)?.preferredMIMEType ?? "image/jpeg")
        }
    }
}
struct FilesSection: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var audio = AudioCapture()
    let targetType: String; let targetID: Int64
    @State private var picking = false
    @State private var camera = false
    @State private var photo: PhotosPickerItem?
    @State private var deleting: OrganizerRecord?
    @State private var recovery: [URL] = []
    @State private var cameraOwner: Int64?
    @State private var fileOwner: Int64?
    var attachments: [OrganizerRecord] { model.records("attachments").filter { $0.text("targetType") == targetType && $0.int("targetId") == targetID } }
    var ownerCreatedAt: Int64? { model.snapshot.record(targetType == "note" ? "notes" : "projects", id: targetID)?.int("createdAt") }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.t("Вложения", "Attachments")).font(.headline)
            ViewThatFits(in: .horizontal) {
                HStack { fileButtons }
                VStack(alignment: .leading) { fileButtons }
            }
            Button {
                if audio.recording { audio.stop() }
                else {
                    let owner = ownerCreatedAt
                    Task {
                        do {
                            try await audio.start(canStart: { !model.locked }) { url, duration in
                                do { try model.attach(AppModel.readFile(url, limit: BackupCodec.maxAttachment), name: "Voice-\(url.lastPathComponent)", mime: "audio/mp4", type: targetType, id: targetID, duration: duration, expectedOwnerCreatedAt: owner); try? FileManager.default.removeItem(at: url) }
                                catch { model.report(error) }
                                refreshRecovery()
                            }
                        } catch { model.notice = AppNotice(message: model.t("Не удалось начать запись. Проверьте разрешение на микрофон в Настройках iPhone.", "Recording could not start. Check microphone permission in iPhone Settings.")) }
                    }
                }
            } label: { Label(audio.recording ? model.t("Остановить и сохранить", "Stop and save") : model.t("Голосовая запись", "Voice recording"), systemImage: audio.recording ? "stop.circle.fill" : "mic.fill") }.buttonStyle(.bordered).tint(audio.recording ? .red : Brand.blue).disabled(audio.preparing)
            if audio.recording { Text(model.t("Идёт запись…", "Recording…")).foregroundStyle(.red) }
            ForEach(attachments) { row in
                Card(color: "cyan") {
                    HStack(alignment: .top) {
                        Button { open(row) } label: {
                            HStack(alignment: .top) { Image(systemName: row.text("mimeType").hasPrefix("audio/") ? "waveform" : row.text("mimeType").hasPrefix("image/") ? "photo" : "doc"); UserText(row.text("displayName")) }.frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain)
                        Button(role: .destructive) { deleting = row } label: { Image(systemName: "trash").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel(model.t("Удалить вложение", "Delete attachment"))
                    }
                    Text(ByteCountFormatter.string(fromByteCount: row.int("size"), countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                    if row.text("mimeType").hasPrefix("audio/") {
                        Button { do { guard let store = model.store else { throw OrganizerError.missingAttachment }; let url = try model.temporary(data: store.attachment(row.text("storedName")), name: row.text("displayName")); try audio.play(url) } catch { model.report(error) } } label: { Label(model.t("Воспроизвести", "Play"), systemImage: "play.circle") }.buttonStyle(.bordered)
                    }
                }
            }
            if audio.playing { HStack { Button(model.t("Остановить", "Stop")) { audio.stopPlayback() }; ProgressView(value: audio.progress) } }
            if !recovery.isEmpty {
                Card(color: "yellow") {
                    Text(model.t("Несохранённые аудиозаписи", "Unsaved recordings")).font(.headline)
                    Text(model.t("Выберите запись, чтобы добавить её к этому объекту. После ошибки файл остаётся на устройстве.", "Choose a recording to attach it here. A failed save keeps the file on this device.")).font(.caption)
                    ForEach(recovery, id: \.self) { url in Button(url.lastPathComponent) { do { try model.attach(AppModel.readFile(url, limit: BackupCodec.maxAttachment), name: url.lastPathComponent, mime: "audio/mp4", type: targetType, id: targetID); try FileManager.default.removeItem(at: url); refreshRecovery() } catch { model.report(error) } } }
                }
            }
        }.fileImporter(isPresented: $picking, allowedContentTypes: [.data], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first { model.addFile(url, type: targetType, id: targetID, expectedOwnerCreatedAt: fileOwner) }
            else if case .failure(let error) = result, (error as NSError).code != NSUserCancelledError { model.report(error) }
        }
        .sheet(isPresented: $camera) { CameraCapture { image in camera = false; guard let data = image?.jpegData(compressionQuality: 0.85) else { return }; do { try model.attach(data, name: "Photo-\(Snapshot.now).jpg", mime: "image/jpeg", type: targetType, id: targetID, expectedOwnerCreatedAt: cameraOwner) } catch { model.report(error) } }.ignoresSafeArea() }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            let owner = ownerCreatedAt
            Task { do { if let value = try await item.loadTransferable(type: ImportedImage.self) { try model.attach(value.data, name: value.name, mime: value.mime, type: targetType, id: targetID, expectedOwnerCreatedAt: owner) } } catch { model.report(error) }; photo = nil }
        }
        .confirmationDialog(model.t("Удалить вложение?", "Delete this attachment?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) { Button(model.t("Удалить", "Delete"), role: .destructive) { if let deleting { model.remove("attachments", id: deleting.id) }; deleting = nil } }
        .onAppear { refreshRecovery() }.onDisappear { audio.stop(); audio.stopPlayback() }
    }
    @ViewBuilder var fileButtons: some View {
        Button { fileOwner = ownerCreatedAt; picking = true } label: { Label(model.t("Файл", "File"), systemImage: "paperclip") }.buttonStyle(.bordered)
        PhotosPicker(selection: $photo, matching: .images, preferredItemEncoding: .current) { Label(model.t("Фото", "Photo"), systemImage: "photo") }.buttonStyle(.bordered)
        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            Button { cameraOwner = ownerCreatedAt; Task { if await AVCaptureDevice.requestAccess(for: .video) { camera = true } else { model.notice = AppNotice(message: model.t("Разрешите доступ к камере в Настройках iPhone.", "Allow camera access in iPhone Settings.")) } } } label: { Label(model.t("Камера", "Camera"), systemImage: "camera") }.buttonStyle(.bordered)
        }
    }
    func open(_ row: OrganizerRecord) {
        do { guard let store = model.store else { throw OrganizerError.missingAttachment }; let url = try model.temporary(data: store.attachment(row.text("storedName")), name: row.text("displayName")); model.show(.preview(url)) }
        catch { model.report(error) }
    }
    func refreshRecovery() { recovery = (try? FileManager.default.contentsOfDirectory(at: AudioCapture.directory, includingPropertiesForKeys: nil))?.filter { $0.pathExtension.lowercased() == "m4a" && $0 != audio.currentURL } ?? [] }
}
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
struct FilePreview: UIViewControllerRepresentable {
    let url: URL
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL; init(_ url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
    func makeCoordinator() -> Coordinator { Coordinator(url) }
    func makeUIViewController(context: Context) -> QLPreviewController { let c = QLPreviewController(); c.dataSource = context.coordinator; return c }
    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {}
}
struct CameraCapture: UIViewControllerRepresentable {
    let completion: (UIImage?) -> Void
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let completion: (UIImage?) -> Void; init(_ completion: @escaping (UIImage?) -> Void) { self.completion = completion }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { completion(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { completion(info[.originalImage] as? UIImage) }
    }
    func makeCoordinator() -> Coordinator { Coordinator(completion) }
    func makeUIViewController(context: Context) -> UIImagePickerController { let c = UIImagePickerController(); c.sourceType = .camera; c.delegate = context.coordinator; return c }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
}
