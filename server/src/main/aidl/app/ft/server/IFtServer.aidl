package app.ft.server;

import android.view.Surface;

interface IFtServer {
    Bundle call(in Bundle request);
    Bundle withSurface(in Surface surface, in Bundle request);
}
