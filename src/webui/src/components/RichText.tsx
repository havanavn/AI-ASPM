import { useMemo } from "react";
import { CKEditor } from "@ckeditor/ckeditor5-react";
import {
  ClassicEditor, Autoformat, BlockQuote, Bold, Code, CodeBlock, Essentials, Heading,
  Image, ImageBlock, ImageCaption, ImageInsertViaUrl, ImageResize, ImageStyle, ImageToolbar,
  ImageUpload, Indent, Italic, Link, List, Markdown, Paragraph, PasteFromOffice,
  Strikethrough, Table, TableColumnResize, TableToolbar, TodoList,
  type Editor, type FileLoader, type UploadAdapter, type UploadResponse,
} from "ckeditor5";
import "ckeditor5/ckeditor5.css";

/**
 * The prose editor: CKEditor 5, storing **Markdown**.
 *
 * <h2>Why Markdown and not the HTML CKEditor produces by default</h2>
 *
 * The content edited here — a finding description, a proof of concept, a comment — is the most
 * attacker-influenced text on the platform. A finding's title and body legitimately contain whatever
 * an attacker put into the application being tested, and an imported finding contains whatever the
 * scanner reported. If the editor stored HTML, the server would have to sanitize HTML: a new,
 * large, and famously error-prone surface, sitting exactly where the hostile content is.
 *
 * The `Markdown` plugin swaps CKEditor's data processor, so `editor.getData()` returns Markdown and
 * `setData()` accepts it. Nothing about the storage or the rendering changes: the server still stores
 * Markdown, still renders it through the one restricted renderer that escapes before it introduces
 * any markup, and remains the only thing that decides what markup exists. CKEditor is an editing
 * convenience over an unchanged pipeline — which is the only role a client-side editor can safely
 * have here.
 *
 * <h2>The toolbar is the subset the renderer supports</h2>
 *
 * Every button corresponds to something `Markdown.java` will actually render. A button producing
 * markup the server then drops is a control that silently loses somebody's work.
 */

/**
 * Uploads an image to the platform and hands CKEditor back a URL.
 *
 * Base64 in a form field rather than multipart, because that is what the endpoint accepts and why:
 * multipart means a parser handling attacker-controlled framing on the request path, for one feature.
 * The server derives the media type from the bytes and refuses anything that is not a raster image,
 * so what comes back is a URL to something it has already decided is safe to serve.
 */
function uploadAdapter(loader: FileLoader, endpoint: string, finding: string | null): UploadAdapter {
  return {
    async upload(): Promise<UploadResponse> {
      const file = await loader.file;
      if (!file) throw new Error("no file");
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(new Error("could not read the file"));
        reader.readAsDataURL(file);
      });
      const body = new URLSearchParams();
      body.set("data", data);
      body.set("filename", file.name);
      body.set("idempotency_key", crypto.randomUUID());
      if (finding) body.set("finding", finding);
      const response = await fetch(endpoint, {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: body.toString(),
      });
      if (!response.ok) {
        // The message a person sees. The server distinguishes "too large" from "not an image"
        // internally and does not say which, because the uploader controls both and neither
        // distinction helps them fix it.
        throw new Error("That file was refused. Images only, up to 1 MB.");
      }
      const payload = (await response.json()) as { url?: string; markdown?: string };
      const url = payload.url ?? payload.markdown?.match(/\((.*)\)/)?.[1];
      if (!url) throw new Error("the upload returned no location");
      return { default: url };
    },
    abort() {},
  };
}

export function RichText({ value, onChange, uploadTo, finding = null, minHeight = "10rem", disabled }: {
  value: string;
  onChange: (markdown: string) => void;
  /** Where an image goes. Omitted leaves the editor text-only rather than offering a broken button. */
  uploadTo?: string;
  finding?: string | null;
  minHeight?: string;
  disabled?: boolean;
}) {
  const config = useMemo(() => ({
    // GPL, declared. CKEditor 5 is dual licensed and refuses to start without this being stated;
    // saying so here is also the honest record of which licence this deployment uses.
    licenseKey: "GPL",
    plugins: [
      Essentials, Paragraph, Heading, Bold, Italic, Strikethrough, Code, CodeBlock,
      Link, List, TodoList, Indent, BlockQuote, Table, TableToolbar, TableColumnResize,
      Image, ImageBlock, ImageCaption, ImageStyle, ImageToolbar, ImageResize, ImageUpload,
      ImageInsertViaUrl, Autoformat, PasteFromOffice, Markdown,
    ],
    toolbar: [
      "undo", "redo", "|",
      "heading", "|",
      "bold", "italic", "strikethrough", "code", "|",
      "link", "bulletedList", "numberedList", "todoList", "|",
      "blockQuote", "codeBlock", "insertTable", ...(uploadTo ? ["uploadImage"] : []), "|",
      "outdent", "indent",
    ],
    heading: {
      options: [
        { model: "paragraph" as const, title: "Paragraph", class: "ck-heading_paragraph" },
        // Starting at h3: the page already owns h1 and h2, and a write-up that emits an h1 breaks
        // the document outline a screen reader navigates by.
        { model: "heading3" as const, view: "h3", title: "Heading", class: "ck-heading_heading3" },
        { model: "heading4" as const, view: "h4", title: "Subheading", class: "ck-heading_heading4" },
      ],
    },
    image: {
      toolbar: ["imageTextAlternative", "|", "imageStyle:inline", "imageStyle:block", "|", "resizeImage"],
    },
    table: { contentToolbar: ["tableColumn", "tableRow", "mergeTableCells"] },
    link: { addTargetToExternalLinks: true, defaultProtocol: "https://" },
  }), [uploadTo]);

  return (
    <div className="rich-text" style={{ ["--ck-min-height" as string]: minHeight }}>
      <CKEditor
        editor={ClassicEditor}
        config={config}
        data={value}
        disabled={disabled}
        onReady={(editor: Editor) => {
          if (!uploadTo) return;
          editor.plugins.get("FileRepository").createUploadAdapter = (loader) =>
            uploadAdapter(loader, uploadTo, finding);
        }}
        onChange={(_event, editor) => onChange(editor.getData())}
      />
    </div>
  );
}
