#pragma once
#include <string>
#include <sstream>

struct BackendProbeResult {
    bool vulkan  = false;
    bool opencl  = false;
    bool hexagon = false;
    std::string cpuFeatures;

    std::string toJson() const {
        std::ostringstream json;
        json << "{\"vulkan\":" << (vulkan ? "true" : "false")
             << ",\"opencl\":" << (opencl ? "true" : "false")
             << ",\"hexagon\":" << (hexagon ? "true" : "false")
             << ",\"cpuFeatures\":\"" << cpuFeatures << "\"}";
        return json.str();
    }
};

class BackendProber {
public:
    static BackendProbeResult probeAll();

    static bool probeVulkan();
    static bool probeOpenCL();
    static bool probeHexagon();
    static std::string probeCpuFeatures();

    static constexpr int VK_LAYERS  = 20;
    static constexpr int CL_LAYERS  = 20;
    static constexpr int HEX_LAYERS = 999;

private:
    static bool tryVulkanInstance();
    static bool tryOpenCLPlatform();
    static bool tryHexagonInit();
};
