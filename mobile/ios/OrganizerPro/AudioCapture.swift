import Foundation
import AVFoundation
import UIKit
import Combine

@MainActor final class AudioCapture: NSObject, ObservableObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    @Published var recording = false
    @Published var preparing = false
    @Published var playing = false
    @Published var progress = 0.0
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var timer: Timer?
    private var observer: NSObjectProtocol?
    private var background: NSObjectProtocol?
    private var completion: ((URL, Int64) -> Void)?
    var currentURL: URL? { recorder?.url }
    static var directory: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OrganizerPro/Recordings", isDirectory: true) }
    override init() {
        super.init()
        observer = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.stop(); self?.stopPlayback() } }
        background = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.stop(); self?.stopPlayback() } }
    }
    deinit { if let observer { NotificationCenter.default.removeObserver(observer) }; if let background { NotificationCenter.default.removeObserver(background) }; timer?.invalidate() }
    func start(completion: @escaping (URL, Int64) -> Void) async throws {
        guard !preparing, !recording else { return }; preparing = true; defer { preparing = false }
        let permitted = await withCheckedContinuation { c in AVAudioApplication.requestRecordPermission { c.resume(returning: $0) } }
        guard permitted else { throw NSError(domain: "OrganizerAudio", code: 1) }
        stopPlayback()
        try FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let url = Self.directory.appendingPathComponent("\(Int(Date().timeIntervalSince1970))-\(UUID().uuidString.prefix(6)).m4a")
        try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker]); try AVAudioSession.sharedInstance().setActive(true)
        let recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64000])
        self.recorder = recorder; recorder.delegate = self; self.completion = completion
        guard recorder.prepareToRecord(), recorder.record() else { self.recorder = nil; throw NSError(domain: "OrganizerAudio", code: 2) }
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        recording = true
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in Task { @MainActor in guard let self, let url = self.recorder?.url else { return }; let size = ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.intValue ?? 0; if size >= 24 * 1024 * 1024 { self.stop() } } }
    }
    func stop() {
        guard let recorder else { return }; let url = recorder.url, duration = Int64(recorder.currentTime * 1000)
        self.recorder = nil; recorder.stop(); recording = false; timer?.invalidate(); timer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        completion?(url, duration); completion = nil
    }
    func play(_ url: URL) throws {
        stop(); stopPlayback(); try AVAudioSession.sharedInstance().setCategory(.playback); try AVAudioSession.sharedInstance().setActive(true)
        let player = try AVAudioPlayer(contentsOf: url); player.delegate = self; self.player = player
        guard player.play() else { throw NSError(domain: "OrganizerAudio", code: 3) }; playing = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in Task { @MainActor in guard let self, let player = self.player else { return }; self.progress = player.duration > 0 ? player.currentTime / player.duration : 0 } }
    }
    func stopPlayback() { player?.stop(); player = nil; playing = false; progress = 0; if !recording { timer?.invalidate(); timer = nil; try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) } }
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) { Task { @MainActor in if self.recorder === recorder { self.stop() } } }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in self.stopPlayback() } }
}
