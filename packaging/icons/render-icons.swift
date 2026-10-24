// Native vector rendering of GrabX's existing GX letterforms, plus a setup variant.
import AppKit
let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
func color(_ hex: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 255)/255, green: CGFloat((hex >> 8) & 255)/255, blue: CGFloat(hex & 255)/255, alpha: 1)
}
func stroke(_ points: [NSPoint], _ width: CGFloat, _ tint: NSColor) {
    let path = NSBezierPath(); path.move(to: points[0]); for point in points.dropFirst() { path.line(to: point) }
    path.lineWidth = width; path.lineCapStyle = .round; path.lineJoinStyle = .round; tint.setStroke(); path.stroke()
}
for setup in [false, true] {
    let image = NSImage(size: NSSize(width: 1024, height: 1024))
    image.lockFocus()
    let context = NSGraphicsContext.current!.cgContext
    context.translateBy(x: 0, y: 1024); context.scaleBy(x: 8, y: -8)
    let panel = NSBezierPath(roundedRect: NSRect(x: 4, y: 4, width: 120, height: 120), xRadius: 28, yRadius: 28)
    NSGradient(starting: color(0x263951), ending: color(0x0c1727))!.draw(in: panel, angle: 60)
    color(0x526d94).setStroke(); panel.lineWidth = 1.5; panel.stroke()
    context.saveGState()
    if setup { context.translateBy(x: 20, y: 2); context.scaleBy(x: 0.68, y: 0.68) }
    let g = NSBezierPath(); g.move(to: NSPoint(x: 58, y: 44))
    g.curve(to: NSPoint(x: 43,y: 38), controlPoint1: NSPoint(x: 53,y: 39), controlPoint2: NSPoint(x: 49,y: 38))
    g.curve(to: NSPoint(x: 23,y: 64), controlPoint1: NSPoint(x: 30,y: 38), controlPoint2: NSPoint(x: 23,y: 48))
    g.curve(to: NSPoint(x: 43,y: 90), controlPoint1: NSPoint(x: 23,y: 80), controlPoint2: NSPoint(x: 30,y: 90))
    g.curve(to: NSPoint(x: 58,y: 84), controlPoint1: NSPoint(x: 49,y: 90), controlPoint2: NSPoint(x: 54,y: 88))
    g.line(to: NSPoint(x: 58,y: 66)); g.line(to: NSPoint(x: 45,y: 66))
    g.lineWidth = 12; g.lineCapStyle = .round; g.lineJoinStyle = .round; color(0xeff5ff).setStroke(); g.stroke()
    stroke([NSPoint(x:77,y:42), NSPoint(x:105,y:86)],12,color(0x9fbaff))
    stroke([NSPoint(x:105,y:42), NSPoint(x:77,y:86)],12,color(0x9fbaff))
    context.restoreGState()
    if setup {
        stroke([NSPoint(x:64,y:76),NSPoint(x:64,y:98)],6,color(0x9bbcff))
        stroke([NSPoint(x:54,y:90),NSPoint(x:64,y:100),NSPoint(x:74,y:90)],6,color(0x9bbcff))
        stroke([NSPoint(x:44,y:103),NSPoint(x:44,y:111),NSPoint(x:84,y:111),NSPoint(x:84,y:103)],4,color(0xcbd9ec))
    }
    image.unlockFocus()
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    image.draw(in: NSRect(x: 0, y: 0, width: 1024, height: 1024))
    NSGraphicsContext.restoreGraphicsState()
    try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent(setup ? "grabx-setup.png" : "grabx.png"))
}
