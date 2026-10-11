package app.ft.server;

import app.ft.server.IFtServer;

interface IFtClient {
    void onServer(IFtServer server, String token);
    void onEvent(in Bundle event);
    void onAudio(in byte[] pcm, int sampleRate, int channels);
}
