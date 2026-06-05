/* Minimal OpenCL stub so libavfilter links on devices without OpenCL ICD. */
#include <stddef.h>

typedef int cl_int;
typedef unsigned int cl_uint;
typedef unsigned long cl_ulong;
typedef void *cl_platform_id;
typedef void *cl_device_id;
typedef void *cl_context;
typedef void *cl_command_queue;
typedef void *cl_mem;
typedef void *cl_program;
typedef void *cl_kernel;
typedef void *cl_event;
typedef void *cl_sampler;
typedef ptrdiff_t cl_ssize_t;

#define CL_SUCCESS 0
#define CL_INVALID_PLATFORM -32

static cl_int not_available(void) { return CL_INVALID_PLATFORM; }

cl_int clBuildProgram(cl_program a, cl_uint b, const cl_device_id *c, const char *d,
                      void (*e)(cl_program, void *), void *f) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f;
  return not_available();
}
cl_mem clCreateBuffer(cl_context a, cl_ulong b, cl_ssize_t c, void *d, cl_int *e) {
  (void)a; (void)b; (void)c; (void)d; if (e) *e = CL_INVALID_PLATFORM; return NULL;
}
cl_command_queue clCreateCommandQueue(cl_context a, cl_device_id b, cl_ulong c, cl_int *d) {
  (void)a; (void)b; (void)c; if (d) *d = CL_INVALID_PLATFORM; return NULL;
}
cl_mem clCreateImage(cl_context a, cl_ulong b, const void *c, const void *d, void *e, cl_int *f) {
  (void)a; (void)b; (void)c; (void)d; (void)e; if (f) *f = CL_INVALID_PLATFORM; return NULL;
}
cl_kernel clCreateKernel(cl_program a, const char *b, cl_int *c) {
  (void)a; (void)b; if (c) *c = CL_INVALID_PLATFORM; return NULL;
}
cl_program clCreateProgramWithSource(cl_context a, cl_uint b, const char **c, const cl_ssize_t *d,
                                     cl_int *e) {
  (void)a; (void)b; (void)c; (void)d; if (e) *e = CL_INVALID_PLATFORM; return NULL;
}
cl_int clEnqueueCopyImage(cl_command_queue a, cl_mem b, cl_mem c, const void *d, const void *e,
                          const void *f, cl_uint g, const cl_event *h, cl_event *i) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)i;
  return not_available();
}
cl_int clEnqueueFillBuffer(cl_command_queue a, cl_mem b, const void *c, cl_ssize_t d, cl_ssize_t e,
                           cl_ssize_t f, cl_uint g, const cl_event *h, cl_event *i) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)i;
  return not_available();
}
cl_int clEnqueueNDRangeKernel(cl_command_queue a, cl_kernel b, cl_uint c, const void *d,
                              const void *e, const void *f, cl_uint g, const cl_event *h,
                              cl_event *i) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)i;
  return not_available();
}
cl_int clEnqueueReadBuffer(cl_command_queue a, cl_mem b, cl_uint c, cl_ssize_t d, cl_ssize_t e,
                           void *f, cl_uint g, const cl_event *h, cl_event *i) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)i;
  return not_available();
}
cl_int clEnqueueWriteBuffer(cl_command_queue a, cl_mem b, cl_uint c, cl_ssize_t d, cl_ssize_t e,
                            const void *f, cl_uint g, const cl_event *h, cl_event *i) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)i;
  return not_available();
}
cl_int clFinish(cl_command_queue a) { (void)a; return not_available(); }
cl_int clFlush(cl_command_queue a) { (void)a; return not_available(); }
cl_int clGetEventProfilingInfo(cl_event a, cl_uint b, cl_ssize_t c, void *d, cl_ssize_t *e) {
  (void)a; (void)b; (void)c; (void)d; (void)e; return not_available();
}
cl_int clGetImageInfo(cl_mem a, cl_uint b, cl_ssize_t c, void *d, cl_ssize_t *e) {
  (void)a; (void)b; (void)c; (void)d; (void)e; return not_available();
}
cl_int clGetMemObjectInfo(cl_mem a, cl_uint b, cl_ssize_t c, void *d, cl_ssize_t *e) {
  (void)a; (void)b; (void)c; (void)d; (void)e; return not_available();
}
cl_int clGetProgramBuildInfo(cl_program a, cl_device_id b, cl_uint c, cl_ssize_t d, void *e,
                             cl_ssize_t *f) {
  (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; return not_available();
}
cl_int clReleaseCommandQueue(cl_command_queue a) { (void)a; return CL_SUCCESS; }
cl_int clReleaseKernel(cl_kernel a) { (void)a; return CL_SUCCESS; }
cl_int clReleaseMemObject(cl_mem a) { (void)a; return CL_SUCCESS; }
cl_int clReleaseProgram(cl_program a) { (void)a; return CL_SUCCESS; }
cl_int clSetKernelArg(cl_kernel a, cl_uint b, cl_ssize_t c, const void *d) {
  (void)a; (void)b; (void)c; (void)d; return not_available();
}
