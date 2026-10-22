import Foundation
import OrganizerCore
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

let args = CommandLine.arguments
do {
    guard args.count >= 4 else { throw OrganizerError.invalid("probe arguments") }
    let store = try OrganizerStore(url: URL(fileURLWithPath: args[2]))
    switch args[1] {
    case "roundtrip":
        try store.restore(BackupCodec.decode(Data(contentsOf: URL(fileURLWithPath: args[3]))))
        try store.export(zip: false).write(to: URL(fileURLWithPath: args[4]))
    case "seed": try store.restore(BackupCodec.decode(Data(contentsOf: URL(fileURLWithPath: args[3]))))
    case "crash": try store.restore(.empty()) { exit(77) }
    case "verify":
        let expected = try BackupCodec.decode(Data(contentsOf: URL(fileURLWithPath: args[3])))
        let actual = try store.fullSnapshot()
        for name in Snapshot.collections + ["attachments"] {
            guard actual.records(name) == expected.records(name) else { throw OrganizerError.invalid("crash recovery \(name)") }
        }
        guard try store.safetyCopies().count == 1 else { throw OrganizerError.invalid("uncommitted safety copy") }
    default: throw OrganizerError.invalid("probe operation")
    }
    print("PASS \(args[1])")
} catch { fputs("\(error)\n", stderr); exit(1) }
