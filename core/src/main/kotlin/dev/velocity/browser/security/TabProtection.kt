package dev.velocity.browser.security
/** UNKNOWNは「危険なし」の証明ができないWebページを表す。状態通知だけでは解除しない。 */
enum class Protection { UNKNOWN, FORM, MEDIA, MICROPHONE, CAMERA, DOWNLOAD, UPLOAD, PAYMENT, WEBAUTHN, PROMPT, PERMISSION, PINNED }
