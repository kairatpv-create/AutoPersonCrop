import Foundation
import Speech
import AVFoundation
import UIKit
import Combine

@MainActor final class SpeechInput: ObservableObject {
    @Published var listening = false
    @Published var transcript = ""
    @Published var error: String?
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var starting = false
    private var tap = false
    private var generation = UUID()
    private var background: NSObjectProtocol?
    init() { background = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in if self?.listening == true { self?.stop() } } } }
    deinit { if let background { NotificationCenter.default.removeObserver(background) } }
    func start(language: String, canStart: @escaping () -> Bool = { true }) {
        guard !starting, !listening else { return }; starting = true; error = nil
        let token = UUID(); generation = token
        Task {
            defer { starting = false }
            let authorization = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
            let microphone = await withCheckedContinuation { c in AVAudioApplication.requestRecordPermission { c.resume(returning: $0) } }
            guard generation == token, canStart() else { return }
            guard authorization == .authorized, microphone, let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language == "ru" ? "ru-RU" : "en-US")), recognizer.isAvailable else { error = ""; return }
            do {
                transcript = ""
                try AVAudioSession.sharedInstance().setCategory(.record, mode: .measurement, options: [.duckOthers]); try AVAudioSession.sharedInstance().setActive(true)
                let request = SFSpeechAudioBufferRecognitionRequest(); request.shouldReportPartialResults = true; request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition; self.request = request
                let input = engine.inputNode, format = engine.inputNode.outputFormat(forBus: 0)
                guard format.sampleRate > 0, format.channelCount > 0 else { throw NSError(domain: "OrganizerSpeech", code: 1) }
                input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }; tap = true
                task = recognizer.recognitionTask(with: request) { [weak self] result, failure in
                    Task { @MainActor in guard let self, self.generation == token else { return }; if let result { self.transcript = result.bestTranscription.formattedString }; if failure != nil || result?.isFinal == true { self.stop(); if failure != nil { self.error = "" } } }
                }
                engine.prepare(); try engine.start(); listening = true
            } catch { stop(); self.error = "" }
        }
    }
    func stop() { generation = UUID(); engine.stop(); if tap { engine.inputNode.removeTap(onBus: 0); tap = false }; request?.endAudio(); task?.cancel(); task = nil; request = nil; listening = false; try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
}
