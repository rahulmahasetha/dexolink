import Foundation
import Networking

public protocol ContinuityAdapter {
    var activityType: String { get }
    func canHandle(activity: ContinuityActivity) -> Bool
    func continueActivity(_ activity: ContinuityActivity) -> Bool
}
