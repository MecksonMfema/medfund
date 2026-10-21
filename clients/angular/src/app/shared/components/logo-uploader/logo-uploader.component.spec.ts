import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LogoUploaderComponent, MAX_LOGO_BYTES } from './logo-uploader.component';

/**
 * The uploader is the only client-side gate in front of a 2MB multipart POST.
 * If its validation silently drifts from tenancy-service's
 * ALLOWED_LOGO_MIMES / MAX_LOGO_BYTES, the admin gets an opaque 400 from the
 * server instead of an actionable message, so pin both rules down here.
 */
describe('LogoUploaderComponent', () => {
  let fixture: ComponentFixture<LogoUploaderComponent>;
  let component: LogoUploaderComponent;

  function fileOf(name: string, type: string, size: number): File {
    const file = new File(['x'], name, { type });
    Object.defineProperty(file, 'size', { value: size });
    return file;
  }

  function dropEvent(file: File): DragEvent {
    const event = new Event('drop') as DragEvent;
    Object.defineProperty(event, 'dataTransfer', {
      value: { files: { item: (i: number) => (i === 0 ? file : null) } },
    });
    return event;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LogoUploaderComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(LogoUploaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('emits a dropped PNG within the size cap', () => {
    const emitted: File[] = [];
    component.fileSelected.subscribe(f => emitted.push(f));

    component.onDrop(dropEvent(fileOf('logo.png', 'image/png', 200 * 1024)));

    expect(emitted.length).toBe(1);
    expect(emitted[0].name).toBe('logo.png');
    expect(component.dragging).toBeFalse();
  });

  it('rejects a non-image file instead of emitting it', () => {
    const emitted: File[] = [];
    const rejections: string[] = [];
    component.fileSelected.subscribe(f => emitted.push(f));
    component.rejected.subscribe(r => rejections.push(r));

    component.onDrop(dropEvent(fileOf('notes.txt', 'text/plain', 10)));

    expect(emitted.length).toBe(0);
    expect(rejections[0]).toContain('notes.txt');
  });

  it('rejects an oversized image before it reaches the wire', () => {
    const emitted: File[] = [];
    const rejections: string[] = [];
    component.fileSelected.subscribe(f => emitted.push(f));
    component.rejected.subscribe(r => rejections.push(r));

    component.onDrop(dropEvent(fileOf('huge.png', 'image/png', MAX_LOGO_BYTES + 1)));

    expect(emitted.length).toBe(0);
    expect(rejections[0]).toContain('2MB');
  });

  it('ignores a drop while an upload is already in flight', () => {
    const emitted: File[] = [];
    component.fileSelected.subscribe(f => emitted.push(f));
    component.uploading = true;

    component.onDrop(dropEvent(fileOf('logo.png', 'image/png', 1024)));

    expect(emitted.length).toBe(0);
  });

  it('clears the input value after a pick so the same file re-fires change', () => {
    const emitted: File[] = [];
    component.fileSelected.subscribe(f => emitted.push(f));
    const input = { files: { item: () => fileOf('logo.svg', 'image/svg+xml', 512) }, value: 'logo.svg' };

    component.onPick({ target: input } as unknown as Event);

    expect(emitted.length).toBe(1);
    expect(input.value).toBe('');
  });
});
