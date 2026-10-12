import { createRoot } from "react-dom/client";
import TranscriptWorkspace from "./transcript-workspace";
import "./globals.css";

const container = document.getElementById("root");
if (!container) throw new Error("Mnote root element was not found.");
createRoot(container).render(<TranscriptWorkspace />);
