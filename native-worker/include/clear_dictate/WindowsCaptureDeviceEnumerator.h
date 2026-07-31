#pragma once

#include "clear_dictate/CaptureDeviceListPayload.h"

#include <stdexcept>
#include <vector>

namespace clear_dictate
{
    /// <summary>
    /// Reports a failure while querying Windows for active microphone endpoints.
    /// The message is intentionally generic so operating-system details do not become part of the application protocol.
    /// </summary>
    class WindowsCaptureDeviceEnumerationException final : public std::runtime_error
    {
    public:
        WindowsCaptureDeviceEnumerationException();
    };

    /// <summary>
    /// Reads active Windows capture endpoints and preserves their exact endpoint identifiers for later recording.
    /// </summary>
    class WindowsCaptureDeviceEnumerator final
    {
    public:
        std::vector<CaptureDeviceDescription> EnumerateActiveCaptureDevices() const;
    };
}
