.PHONY: ffmpeg-sdk-cli

ffmpeg-sdk-cli: $(OBJS-ffmpeg) $(FF_DEP_LIBS)
	$(CC) $(LDFLAGS) -shared -Wl,-soname,libffmpeg_sdk_cli.so \
		-Wl,--version-script="$(SDK_CLI_MAP)" \
		-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
		-Wl,-z,relro -Wl,-z,now -o "$(SDK_CLI_OUT)" \
		$(OBJS-ffmpeg) $(FF_EXTRALIBS)
