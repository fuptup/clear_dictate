#include "clear_dictate/CaptureDeviceListPayload.h"
#include "clear_dictate/WindowsCaptureDeviceEnumerator.h"

#include <fcntl.h>
#include <io.h>

#include <cstdint>
#include <exception>
#include <iostream>
#include <vector>

int main(int argumentCount, char**)
{
    try
    {
        if (argumentCount != 1)
        {
            std::cerr << "The audio-device enumerator does not accept arguments.\n";
            return 2;
        }
        if (_setmode(_fileno(stdout), _O_BINARY) == -1)
        {
            std::cerr << "Could not configure binary output.\n";
            return 3;
        }

        const clear_dictate::WindowsCaptureDeviceEnumerator deviceEnumerator;
        const std::vector<clear_dictate::CaptureDeviceDescription> devices = deviceEnumerator.EnumerateActiveCaptureDevices();
        const std::vector<std::uint8_t> payload = clear_dictate::EncodeCaptureDeviceList(devices);
        std::cout.write(reinterpret_cast<const char*>(payload.data()), static_cast<std::streamsize>(payload.size()));
        std::cout.flush();
        return std::cout.good() ? 0 : 4;
    }
    catch (const std::exception& exception)
    {
        std::cerr << "Audio-device enumeration failed: " << exception.what() << '\n';
        return 1;
    }
}
