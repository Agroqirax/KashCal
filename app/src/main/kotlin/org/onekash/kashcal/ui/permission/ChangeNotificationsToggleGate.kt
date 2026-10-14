package org.onekash.kashcal.ui.permission

/**
 * What turning a notification affordance on needs: the Change notifications switch, or the
 * Calendar notifications banner's action.
 */
enum class ChangeNotificationsStep {
    TurnOn,
    TurnOff,
    /** Ask for POST_NOTIFICATIONS; the host opens the app's settings once it can't be asked. */
    RequestPermission,
    /** The app's notifications are off in system settings. */
    OpenAppSettings,
    /** The caller's channel (Calendar updates, or event reminders) is blocked in settings. */
    OpenChannelSettings
}

/**
 * Returns the step for a tap that sets the switch to [enable]. Turning it on goes straight through
 * only when a notification would show; otherwise the step names what is missing, permission
 * first (on Android 13 and later a missing permission also turns the app's notifications off).
 * [channelEnabled] is the state of the caller's channel.
 * [permissionGranted] is true below Android 13, where nothing is asked for
 * ([PermissionChecker.hasNotificationPermission]).
 */
fun changeNotificationsToggleStep(
    enable: Boolean,
    appNotificationsEnabled: Boolean,
    channelEnabled: Boolean,
    permissionGranted: Boolean,
): ChangeNotificationsStep = when {
    !enable -> ChangeNotificationsStep.TurnOff
    !permissionGranted -> ChangeNotificationsStep.RequestPermission
    !appNotificationsEnabled -> ChangeNotificationsStep.OpenAppSettings
    !channelEnabled -> ChangeNotificationsStep.OpenChannelSettings
    else -> ChangeNotificationsStep.TurnOn
}
