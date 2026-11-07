// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
#import "CatpawRuntimePlugin.h"
#import <NodeMobile/NodeMobile.h>
#include <vector>
#include <cstring>

@implementation CatpawRuntimePlugin
static BOOL started = NO;
static BOOL exited = NO;
static NSString *runtimeRoot;

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
  FlutterMethodChannel *channel = [FlutterMethodChannel methodChannelWithName:@"marco_movie/catpaw"
      binaryMessenger:[registrar messenger]];
  [registrar addMethodCallDelegate:[[CatpawRuntimePlugin alloc] init] channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall *)call result:(FlutterResult)result {
  if (![call.method isEqualToString:@"start"]) { result(FlutterMethodNotImplemented); return; }
  NSDictionary *args = [call.arguments isKindOfClass:[NSDictionary class]] ? call.arguments : nil;
  NSString *root = args[@"root"], *script = args[@"script"], *ready = args[@"ready"];
  if (![root isKindOfClass:[NSString class]] || ![script isKindOfClass:[NSString class]] ||
      ![ready isKindOfClass:[NSString class]] ||
      ![script isEqualToString:[root stringByAppendingPathComponent:@"host.cjs"]] ||
      ![ready isEqualToString:[root stringByAppendingPathComponent:@"ready.json"]]) {
    result([FlutterError errorWithCode:@"invalid_path" message:@"CatPaw 路径无效" details:nil]); return;
  }
  @synchronized([CatpawRuntimePlugin class]) {
    if (exited || (started && ![runtimeRoot isEqualToString:root])) {
      result([FlutterError errorWithCode:@"restart_required" message:@"请重新打开应用以启动 CatPaw" details:nil]); return;
    }
    if (!started) {
      [[NSFileManager defaultManager] removeItemAtPath:ready error:nil];
      runtimeRoot = [root copy]; started = YES;
      NSArray<NSString *> *arguments = @[@"node", @"--jitless", script, root, ready, @"--embedded"];
      NSThread *thread = [[NSThread alloc] initWithBlock:^{
        @autoreleasepool {
          // node_start requires contiguous argv storage; keep it alive until Node exits.
          size_t size = 0;
          for (NSString *argument in arguments) size += strlen(argument.UTF8String) + 1;
          std::vector<char> buffer(size);
          std::vector<char *> argv(arguments.count + 1, nullptr);
          char *cursor = buffer.data();
          for (NSUInteger i = 0; i < arguments.count; i++) {
            argv[i] = cursor;
            size_t count = strlen(arguments[i].UTF8String) + 1;
            memcpy(cursor, arguments[i].UTF8String, count); cursor += count;
          }
          node_start((int)arguments.count, argv.data());
          @synchronized([CatpawRuntimePlugin class]) { exited = YES; }
        }
      }];
      thread.stackSize = 8 * 1024 * 1024;
      thread.name = @"catpaw-node";
      [thread start];
    }
  }
  result(nil);
}
@end
