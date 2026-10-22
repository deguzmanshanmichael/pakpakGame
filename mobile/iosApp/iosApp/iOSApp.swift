import SwiftUI
import shared

@main
struct PakPakApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some SwiftUI.Scene {
        WindowGroup {
            GameView()
                .ignoresSafeArea()
                .statusBarHidden(true)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: MainViewControllerKt.onAppResume()
            case .inactive, .background: MainViewControllerKt.onAppPause()
            @unknown default: break
            }
        }
    }
}

/** Hosts the shared Kotlin/Compose game. The backend URL comes from Info.plist (set per build config). */
struct GameView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let url = Bundle.main.object(forInfoDictionaryKey: "PakPakApiBaseUrl") as? String ?? ""
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        return MainViewControllerKt.MainViewController(apiBaseUrl: url, versionName: version)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
