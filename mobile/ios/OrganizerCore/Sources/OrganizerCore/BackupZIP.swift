import Foundation
import CZlib

public enum BackupZIP {
    private static func bad() -> OrganizerError { .invalid("ZIP") }
    private static func u16(_ data: Data, _ offset: Int) throws -> Int {
        guard offset >= 0, offset + 2 <= data.count else { throw bad() }
        return Int(data[offset]) | Int(data[offset + 1]) << 8
    }
    private static func u32(_ data: Data, _ offset: Int) throws -> Int {
        try u16(data, offset) | u16(data, offset + 2) << 16
    }
    private static func crc(_ data: Data) -> UInt32 {
        data.withUnsafeBytes { bytes in UInt32(truncatingIfNeeded: crc32(0, bytes.bindMemory(to: Bytef.self).baseAddress, uInt(data.count))) }
    }
    private static func transform(_ data: Data, inflate reading: Bool, limit: Int) throws -> Data {
        var stream = z_stream()
        let result = reading ? inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) : deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard result == Z_OK else { throw bad() }
        defer { if reading { inflateEnd(&stream) } else { deflateEnd(&stream) } }
        return try data.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(data.count)
            var output = Data(), chunk = [UInt8](repeating: 0, count: 8192)
            while true {
                let status: Int32 = chunk.withUnsafeMutableBytes { target in
                    stream.next_out = target.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = 8192
                    return reading ? inflate(&stream, Z_NO_FLUSH) : deflate(&stream, Z_FINISH)
                }
                let count = 8192 - Int(stream.avail_out)
                guard count <= limit - output.count else { throw OrganizerError.tooLarge }
                output.append(contentsOf: chunk.prefix(count))
                if status == Z_STREAM_END { guard stream.avail_in == 0 else { throw bad() }; return output }
                guard status == Z_OK, count > 0 || stream.avail_in > 0 else { throw bad() }
            }
        }
    }
    public static func read(_ data: Data) throws -> Data {
        guard data.count >= 22, data.count <= BackupCodec.maxJSON else { throw OrganizerError.tooLarge }
        var end: Int?
        for offset in stride(from: data.count - 22, through: max(0, data.count - 65_557), by: -1) {
            if try u32(data, offset) == 0x06054b50, try offset + 22 + u16(data, offset + 20) == data.count { end = offset; break }
        }
        guard let end else { throw bad() }
        let count = try u16(data, end + 10), size = try u32(data, end + 12), start = try u32(data, end + 16)
        guard try u16(data, end + 4) == 0, try u16(data, end + 6) == 0, try u16(data, end + 8) == count,
              count > 0, count <= 32, size <= 256 * 1024, start + size == end else { throw bad() }
        var cursor = start, document: Data?
        for _ in 0..<count {
            guard try u32(data, cursor) == 0x02014b50 else { throw bad() }
            let flags = try u16(data, cursor + 8), method = try u16(data, cursor + 10), expectedCRC = try u32(data, cursor + 16)
            let packed = try u32(data, cursor + 20), unpacked = try u32(data, cursor + 24), nameCount = try u16(data, cursor + 28)
            let extra = try u16(data, cursor + 30), comment = try u16(data, cursor + 32), local = try u32(data, cursor + 42)
            guard flags & ~0x808 == 0, [0, 8].contains(method), unpacked <= BackupCodec.maxJSON, packed <= BackupCodec.maxJSON,
                  cursor + 46 + nameCount + extra + comment <= end else { throw bad() }
            let name = data.subdata(in: cursor + 46..<cursor + 46 + nameCount)
            guard let path = String(data: name, encoding: .utf8), try u32(data, local) == 0x04034b50,
                  try u16(data, local + 6) == flags, try u16(data, local + 8) == method, try u16(data, local + 26) == nameCount else { throw bad() }
            let localExtra = try u16(data, local + 28), offset = local + 30 + nameCount + localExtra
            guard local >= 0, offset >= local, offset + packed <= start,
                  data.subdata(in: local + 30..<local + 30 + nameCount) == name else { throw bad() }
            if path.hasSuffix("/") { guard packed == 0, unpacked == 0 else { throw bad() } }
            else {
                guard document == nil else { throw bad() }
                let bytes = data.subdata(in: offset..<offset + packed)
                let result = method == 8 ? try transform(bytes, inflate: true, limit: unpacked) : bytes
                guard result.count == unpacked, Int(crc(result)) == expectedCRC else { throw bad() }
                document = result
            }
            cursor += 46 + nameCount + extra + comment
        }
        guard cursor == end, let document else { throw bad() }
        return document
    }
    public static func write(_ data: Data) throws -> Data {
        guard data.count <= BackupCodec.maxJSON else { throw OrganizerError.tooLarge }
        let packed = try transform(data, inflate: false, limit: BackupCodec.maxJSON)
        let name = Data("organizer-backup.json".utf8), checksum = Int(crc(data))
        var out = Data()
        func word(_ value: Int) { out.append(UInt8(truncatingIfNeeded: value)); out.append(UInt8(truncatingIfNeeded: value >> 8)) }
        func long(_ value: Int) { word(value); word(value >> 16) }
        long(0x04034b50); word(20); word(0x800); word(8); word(0); word(33); long(checksum)
        long(packed.count); long(data.count); word(name.count); word(0); out.append(name); out.append(packed)
        let start = out.count
        long(0x02014b50); word(20); word(20); word(0x800); word(8); word(0); word(33); long(checksum)
        long(packed.count); long(data.count); word(name.count); word(0); word(0); word(0); word(0); long(0); long(0); out.append(name)
        let size = out.count - start
        long(0x06054b50); word(0); word(0); word(1); word(1); long(size); long(start); word(0)
        return out
    }
}
