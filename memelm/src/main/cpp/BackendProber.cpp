#include "BackendProber.h"
#include "logging.h"

#include <dlfcn.h>
#include <string>
#include <cstring>
#include <fstream>
#include <sstream>

// ---------------------------------------------------------------------------
// Vulkan probe
// ---------------------------------------------------------------------------

typedef struct VkInstance_T* VkInstance;
typedef struct VkPhysicalDevice_T* VkPhysicalDevice;

typedef int VkResult;
#define VK_SUCCESS 0

typedef struct VkApplicationInfo {
    int sType;
    const void* pNext;
    const char* pApplicationName;
    unsigned int applicationVersion;
    const char* pEngineName;
    unsigned int engineVersion;
    unsigned int apiVersion;
} VkApplicationInfo;

typedef struct VkInstanceCreateInfo {
    int sType;
    const void* pNext;
    unsigned int flags;
    const VkApplicationInfo* pApplicationInfo;
    unsigned int enabledLayerCount;
    const char* const* ppEnabledLayerNames;
    unsigned int enabledExtensionCount;
    const char* const* ppEnabledExtensionNames;
} VkInstanceCreateInfo;

typedef VkResult (*vkCreateInstanceFn)(const VkInstanceCreateInfo*, const void*, VkInstance*);
typedef void (*vkDestroyInstanceFn)(VkInstance, const void*);
typedef VkResult (*vkEnumeratePhysicalDevicesFn)(VkInstance, unsigned int*, VkPhysicalDevice*);

static void* s_vulkanLib = nullptr;

bool BackendProber::tryVulkanInstance() {
    s_vulkanLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!s_vulkanLib) {
        LOGi("BackendProber: Vulkan library not found");
        return false;
    }

    auto vkCreateInstance = reinterpret_cast<vkCreateInstanceFn>(
        dlsym(s_vulkanLib, "vkCreateInstance"));
    auto vkDestroyInstance = reinterpret_cast<vkDestroyInstanceFn>(
        dlsym(s_vulkanLib, "vkDestroyInstance"));
    auto vkEnumeratePhysicalDevices = reinterpret_cast<vkEnumeratePhysicalDevicesFn>(
        dlsym(s_vulkanLib, "vkEnumeratePhysicalDevices"));

    if (!vkCreateInstance || !vkDestroyInstance || !vkEnumeratePhysicalDevices) {
        LOGe("BackendProber: Vulkan symbols not found");
        dlclose(s_vulkanLib);
        s_vulkanLib = nullptr;
        return false;
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = 0; // VK_STRUCTURE_TYPE_APPLICATION_INFO
    appInfo.pApplicationName = "MemeChat";
    appInfo.applicationVersion = 1;
    appInfo.apiVersion = 0x00400000; // VK_API_VERSION_1_0

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = 1; // VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = nullptr;
    VkResult res = vkCreateInstance(&createInfo, nullptr, &instance);
    if (res != VK_SUCCESS || !instance) {
        LOGe("BackendProber: vkCreateInstance failed: %d", res);
        dlclose(s_vulkanLib);
        s_vulkanLib = nullptr;
        return false;
    }

    unsigned int deviceCount = 0;
    res = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (res != VK_SUCCESS || deviceCount == 0) {
        LOGe("BackendProber: no Vulkan physical devices found");
        vkDestroyInstance(instance, nullptr);
        dlclose(s_vulkanLib);
        s_vulkanLib = nullptr;
        return false;
    }

    LOGi("BackendProber: Vulkan OK (%d device(s))", deviceCount);
    vkDestroyInstance(instance, nullptr);
    return true;
}

// ---------------------------------------------------------------------------
// OpenCL probe
// ---------------------------------------------------------------------------

typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef int cl_int;

#define CL_SUCCESS 0
#define CL_DEVICE_TYPE_GPU (1 << 2)

typedef cl_int (*clGetPlatformIDsFn)(unsigned int, cl_platform_id*, unsigned int*);
typedef cl_int (*clGetDeviceIDsFn)(cl_platform_id, cl_int, unsigned int, cl_device_id*, unsigned int*);

bool BackendProber::tryOpenCLPlatform() {
    void* lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        LOGi("BackendProber: OpenCL library not found");
        return false;
    }

    auto clGetPlatformIDs = reinterpret_cast<clGetPlatformIDsFn>(
        dlsym(lib, "clGetPlatformIDs"));
    auto clGetDeviceIDs = reinterpret_cast<clGetDeviceIDsFn>(
        dlsym(lib, "clGetDeviceIDs"));

    if (!clGetPlatformIDs || !clGetDeviceIDs) {
        dlclose(lib);
        return false;
    }

    unsigned int platformCount = 0;
    cl_int res = clGetPlatformIDs(0, nullptr, &platformCount);
    if (res != CL_SUCCESS || platformCount == 0) {
        dlclose(lib);
        return false;
    }

    cl_platform_id platforms[16];
    res = clGetPlatformIDs(platformCount > 16 ? 16 : platformCount, platforms, &platformCount);
    if (res != CL_SUCCESS) {
        dlclose(lib);
        return false;
    }

    for (unsigned int i = 0; i < platformCount; i++) {
        unsigned int deviceCount = 0;
        res = clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, 0, nullptr, &deviceCount);
        if (res == CL_SUCCESS && deviceCount > 0) {
            LOGi("BackendProber: OpenCL GPU found on platform %u", i);
            dlclose(lib);
            return true;
        }
    }

    dlclose(lib);
    return false;
}

// ---------------------------------------------------------------------------
// Hexagon probe
// ---------------------------------------------------------------------------

bool BackendProber::tryHexagonInit() {
    static const char* skelLibs[] = {
        "libggml-htp-v81.so",
        "libggml-htp-v79.so",
        "libggml-htp-v75.so",
        "libggml-htp-v73.so",
        nullptr
    };

    for (int i = 0; skelLibs[i]; i++) {
        void* lib = dlopen(skelLibs[i], RTLD_NOW | RTLD_LOCAL);
        if (lib) {
            LOGi("BackendProber: Hexagon skel found: %s", skelLibs[i]);
            dlclose(lib);
            return true;
        }
    }

    LOGi("BackendProber: No Hexagon HTP skel found");
    return false;
}

// ---------------------------------------------------------------------------
// CPU features probe
// ---------------------------------------------------------------------------

std::string BackendProber::probeCpuFeatures() {
    std::ifstream cpuinfo("/proc/cpuinfo");
    if (!cpuinfo.is_open()) return "";

    std::string line;
    while (std::getline(cpuinfo, line)) {
        if (line.find("Features") == 0 || line.find("features") == 0) {
            auto colon = line.find(':');
            if (colon != std::string::npos) {
                std::string features = line.substr(colon + 1);
                while (!features.empty() && features[0] == ' ') {
                    features = features.substr(1);
                }
                return features;
            }
        }
    }
    return "";
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

bool BackendProber::probeVulkan() {
    return tryVulkanInstance();
}

bool BackendProber::probeOpenCL() {
    return tryOpenCLPlatform();
}

bool BackendProber::probeHexagon() {
    return tryHexagonInit();
}

BackendProbeResult BackendProber::probeAll() {
    BackendProbeResult result;
    result.vulkan      = probeVulkan();
    result.opencl      = probeOpenCL();
    result.hexagon     = probeHexagon();
    result.cpuFeatures = probeCpuFeatures();

    LOGi("BackendProber: Vulkan=%s OpenCL=%s Hexagon=%s CPU=%s",
         result.vulkan ? "YES" : "NO",
         result.opencl ? "YES" : "NO",
         result.hexagon ? "YES" : "NO",
         result.cpuFeatures.c_str());
    return result;
}
